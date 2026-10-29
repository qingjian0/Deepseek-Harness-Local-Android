/*
 * dsh_pty.c —— PTY 进程派生层
 *
 * 职责：在 app 进程内 fork 出一个挂载伪终端的子进程（node 引擎），
 * 返回 master fd 给 Java 层读写；提供 wait/kill 生命周期原语。
 *
 * 设计要点：
 * - targetSdk 28 下 SELinux 允许对 filesDir 内文件 execve（Termux 同款豁免）
 * - 单引擎进程假设：全局只跟踪一个 child pid，接口保持最小
 * - 子进程 setsid + TIOCSCTTY 使其成为会话首进程，node-pty 类库需要真实 tty 语义
 */
#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <android/log.h>

#define LOG_TAG "dshpty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static pid_t g_child = -1;

/* 从 jobjectArray 读出 char**（UTF-8），失败返回 NULL。
 *
 * ⚠️ 绝对不要把 NULL 交给 ReleaseStringUTFChars —— JNI 规范下这是 UB，
 * 会崩在 native 层，日志里连 Java 堆栈都没有（只剩一个 SIGSEGV）。
 * GetStringUTFChars 返回 NULL 只有两种可能：OOM 或已有待处理异常，
 * 两种都不该继续往下走，所以这里清理已分配内容后返回 NULL，由调用方兜底。 */
static char **read_string_array(JNIEnv *env, jobjectArray arr, jsize *outLen) {
    jsize len = (*env)->GetArrayLength(env, arr);
    char **vec = (char **) calloc((size_t) len + 1, sizeof(char *));
    if (!vec) return NULL;
    jsize i = 0;
    for (; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (!js) {                       /* 取不到元素（异常/越界）→ 视作空串 */
            if ((*env)->ExceptionCheck(env)) goto fail;
            vec[i] = strdup("");
            if (!vec[i]) goto fail;
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        if (!utf) {                      /* OOM 或待处理异常 → 停止 */
            (*env)->DeleteLocalRef(env, js);
            goto fail;
        }
        vec[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, js, utf);   /* utf 非 NULL 才走到这 */
        (*env)->DeleteLocalRef(env, js);
        if (!vec[i]) goto fail;
    }
    *outLen = len;
    return vec;
fail:
    for (jsize k = 0; k <= i; k++) free(vec[k]);       /* vec 全 NULL 初始化，free(NULL) 安全 */
    free(vec);
    *outLen = 0;
    return NULL;
}

static void free_string_array(char **vec) {
    if (!vec) return;
    for (int i = 0; vec[i]; i++) free(vec[i]);
    free(vec);
}

/*
 * 关掉所有 > stderr 的继承 fd。
 *
 * 为什么必须在 execve 前做（2026-10-09 实测事故）：app 进程里的
 * `java.net.ServerSocket`（AgentBridge 的 127.0.0.1:3083）**不带 O_CLOEXEC**
 * —— 实测该 listen socket 的 fdinfo `flags: 04002`（O_RDWR|O_NONBLOCK，没有
 * O_CLOEXEC）。fork 之后引擎把这块 socket 一起带过去了，于是：
 *   AI 随便起的子进程只要还有一个活着，3083 就**永不释放**；
 *   引擎重启时新桥 bind 失败（EADDRINUSE），notify / scr / say 全部哑掉。
 * 关在 execve 前，等于把「引擎及其全部后代」一次性摘干净 —— 后代从引擎继承，
 * 引擎干净了它们自然干净。
 *
 * 用 /proc/self/fd 而不是 close_range(2)：后者要 Linux 5.9+，Android 上跨度太大。
 */
static void close_inherited_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return;
    int dfd = dirfd(d);
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd > STDERR_FILENO && fd != dfd) close(fd);
    }
    closedir(d);
}

/*
 * 派生 PTY 子进程。
 * cmd: 可执行文件绝对路径; args: argv[1..]; cwd: 工作目录;
 * env: "KEY=VALUE" 数组; rows/cols: 终端尺寸。
 * 成功返回 master fd，失败返回负的 errno 取反（Java 层取 abs 得 errno）。
 */
JNIEXPORT jint JNICALL
Java_dsh_dsh_mobile_engine_Pty_nativeForkPty(
        JNIEnv *env, jclass clazz,
        jstring jCmd, jobjectArray jArgs, jstring jCwd,
        jobjectArray jEnv, jint rows, jint cols) {

    const char *cmd = NULL;
    const char *cwd = NULL;
    jsize argc = 0, envc = 0;
    char **argv = NULL;
    char **envp = NULL;
    char **fullArgv = NULL;
    int master = -1;

    cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    cwd = (*env)->GetStringUTFChars(env, jCwd, NULL);
    /* 同上：NULL 意味着 OOM / 待处理异常，不能 strdup(NULL) 也不能往下走 */
    if (!cmd || !cwd) { errno = ENOMEM; goto fail_errno; }

    argv = read_string_array(env, jArgs, &argc);
    envp = read_string_array(env, jEnv, &envc);
    /* 原来这里没查返回值：失败时 argv==NULL，fullArgv[i+1]=argv[i] 直接解引用崩溃 */
    if (!argv || !envp) { errno = ENOMEM; goto fail_errno; }

    /* argv[0] 必须是程序名本身 */
    fullArgv = (char **) calloc((size_t) argc + 2, sizeof(char *));
    if (!fullArgv) { errno = ENOMEM; goto fail_errno; }
    fullArgv[0] = strdup(cmd);
    if (!fullArgv[0]) { errno = ENOMEM; goto fail_errno; }
    for (jsize i = 0; i < argc; i++) fullArgv[i + 1] = argv[i];

    signal(SIGPIPE, SIG_IGN);

    master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) goto fail_errno;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master); master = -1;
        goto fail_errno;
    }

    char slavePath[128];
    if (ptsname_r(master, slavePath, sizeof(slavePath)) != 0) {
        close(master); master = -1;
        goto fail_errno;
    }

    /* 预设窗口尺寸，避免 node-pty 初次 resize 前读到 0x0 */
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) { close(master); master = -1; goto fail_errno; }

    if (pid == 0) {
        /* ---- 子进程 ----
         * 顺序讲究：setsid → 清继承 fd → 才开 slave。
         * 若先开 slave，close_inherited_fds() 会把它一起关掉。 */
        setsid();
        /* 含父进程传下来的 master：这里一并关掉，下面重开 slave（会拿到最小的
         * 可用 fd，通常是 3），再 dup2 到 0/1/2。 */
        close_inherited_fds();
        int slave = open(slavePath, O_RDWR);
        if (slave < 0) _exit(126);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        ioctl(STDIN_FILENO, TIOCSCTTY, 0);   /* 设为控制终端 */
        /* 父进程死了要跟着死。setsid 之后子进程已脱离会话，没人回收就是孤儿
         * —— 而孤儿会一直攥着继承来的 3080/3083 监听 socket 不放，端口再也
         * 起不来。SIGTERM 而非 SIGKILL：给引擎机会收尾（落盘、断连）。 */
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        /* 竞态：prctl 生效前父进程可能已经没了，那时 PDEATHSIG 不会补发信号 */
        if (getppid() == 1) _exit(0);
        if (chdir(cwd) != 0) _exit(125);
        execve(cmd, fullArgv, envp);
        _exit(127);                          /* execve 失败 */
    }

    /* ---- 父进程 ---- */
    g_child = pid;
    /* fullArgv[0] 单独 strdup；fullArgv[1..] 直接引用 argv 内指针，
       因此先 free 容器自身 + 首元素，再交 free_string_array(argv) 统一释放剩余 */
    if (fullArgv) { free(fullArgv[0]); free(fullArgv); }
    free_string_array(argv); free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    return master;

fail_errno:
    /* 调用方按「负 errno」判定失败：errno 意外为 0 时会给 Java 层回 0 = 假成功 */
    if (errno == 0) errno = EINVAL;
    LOGE("nativeForkPty failed: %s", strerror(errno));
    if (fullArgv) { free(fullArgv[0]); free(fullArgv); }
    free_string_array(argv); free_string_array(envp);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    return -errno;
}

/* 当前跟踪的子进程 PID（供 Java 层进程组/树级清理） */
JNIEXPORT jint JNICALL
Java_dsh_dsh_mobile_engine_Pty_nativeChildPid(JNIEnv *env, jclass clazz) {
    return (g_child > 0) ? (jint)g_child : -1;
}

/* 阻塞等待子进程退出，返回原始 waitpid status（Java 层解析） */
JNIEXPORT jint JNICALL
Java_dsh_dsh_mobile_engine_Pty_nativeWaitChild(JNIEnv *env, jclass clazz) {
    if (g_child <= 0) return -1;
    int status = 0;
    if (waitpid(g_child, &status, 0) < 0) return -errno;
    g_child = -1;
    return status;
}

/* 向子进程发送信号（SIGTERM=15 / SIGKILL=9） */
JNIEXPORT void JNICALL
Java_dsh_dsh_mobile_engine_Pty_nativeSignalChild(JNIEnv *env, jclass clazz, jint sig) {
    if (g_child > 0) kill(g_child, sig);
}

/* Java 层关闭 master fd 后子进程收到 SIGHUP 会自然退出，此处兜底强杀 */
JNIEXPORT void JNICALL
Java_dsh_dsh_mobile_engine_Pty_nativeForceKill(JNIEnv *env, jclass clazz) {
    if (g_child > 0) kill(g_child, SIGKILL);
}
