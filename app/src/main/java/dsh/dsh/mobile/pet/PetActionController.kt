package dsh.dsh.mobile.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import dsh.dsh.mobile.R

/**
 * 把 dsh-pet-bridge 的事件翻译成「表情 + 气泡 + 工作状态档位」。
 *
 * 动画播放本身不再由这里负责 —— 交给 [PetAnimChain]，避免两套定时器互相打架：
 * 这里只声明"现在是什么工作状态"，链决定播哪一段、播完后怎么轮换/恢复。
 *
 * 气泡文案（protocol 3+）：
 *  - `text` 有值（dsh-pet 任务详情，或桥端自发布的随机台词）→ 直接显示；
 *  - `text` 为空（桥端 selfPublishRandomLines=false 或老版本插件）→ 回落到
 *    台词库（[PetLines]）的 ws_* 六档——用户在桌宠设置 → 台词库里改措辞即可，
 *    内置台词与 dsh-pet web workStatusTexts 同源。
 *
 * 时序规则（实测 dsh-pet-bridge 的行为）：
 *  - user/pre 连续到达时，以最新为准；
 *  - post 只代表「一步完成」，没有工具名，不能拼成「已读 xxx」；
 *  - stop 可能来自多个 agent，因此延迟 1.5s 再庆祝，给同回合后续事件留出窗口；
 *  - 引擎被杀时不会再有 stop，由 Service 每 30s 调 [onTick] 兜底回待机。
 */
class PetActionController(
    context: Context,
    private val packProvider: () -> PetPackConfig?,
    private val onWork: (PetAnimChain.WorkSlot?) -> Unit,
    private val onFace: (PetView.Face) -> Unit,
    private val onBubble: (String?) -> Unit,
) {
    private val appCtx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var turnActive = false
    private var current: PetView.Face = PetView.Face.IDLE
    private var lastEventAt = 0L
    private var pendingStop: Runnable? = null
    private var pendingAck: Runnable? = null

    fun start() {
        idle()
    }

    fun onEvent(event: PetEvent) {
        cancelPendingStop()
        cancelPendingAck()
        lastEventAt = System.currentTimeMillis()
        when (event.kind) {
            "user" -> {
                turnActive = true
                thinking(event)
            }
            "pre" -> {
                turnActive = true
                working(event)
            }
            "post" -> ack(event)
            "stop" -> {
                turnActive = false
                scheduleStop(event)
            }
            "error" -> {
                turnActive = false
                error(event)
            }
            "waiting" -> {
                face(PetView.Face.EXPLORING)
                work(PetAnimChain.WorkSlot.WAITING)
                bubble(event.text ?: pick(LINE_WAITING))
            }
        }
    }

    /** 每 30s 由 Service 调用；引擎不在时把卡住的工作态收回待机。 */
    fun onTick(engineRunning: Boolean) {
        if (current == PetView.Face.IDLE) return
        val stale = System.currentTimeMillis() - lastEventAt > 15 * 60_000L
        if (!engineRunning || stale) {
            turnActive = false
            idle()
        }
    }

    /** 引擎回合是否进行中（漫游/碎碎念据此让路）。 */
    fun isTurnActive(): Boolean = turnActive

    fun reset() {
        cancelPendingStop()
        cancelPendingAck()
        turnActive = false
        idle()
    }

    // ------------------------------------------------------------ 状态

    private fun idle() {
        face(PetView.Face.IDLE)
        work(null)
        bubble(null)
    }

    /** protocol 3：桥侧带 text（任务详情/桥端随机台词）时直显，否则用台词库 ws_thinking。 */
    private fun thinking(event: PetEvent? = null) {
        face(PetView.Face.THINKING)
        work(PetAnimChain.WorkSlot.THINKING)
        bubble(event?.text ?: pick(LINE_THINKING))
    }

    private fun working(event: PetEvent) {
        face(PetView.Face.WORKING)
        work(PetAnimChain.WorkSlot.WORKING)
        bubble(event.text ?: pick(LINE_WORKING))
    }

    /** 「一步完成」：短暂切到整理的档位，之后自行回到工作 / 空闲。 */
    private fun ack(event: PetEvent? = null) {
        face(PetView.Face.ACK)
        work(PetAnimChain.WorkSlot.RESULT)
        bubble(event?.text ?: pick(LINE_RESULT))
        cancelPendingAck()
        val r = Runnable {
            pendingAck = null
            if (turnActive) {
                face(PetView.Face.WORKING)
                work(PetAnimChain.WorkSlot.WORKING)
                bubble(pick(LINE_WORKING))
            } else {
                idle()
            }
        }
        pendingAck = r
        handler.postDelayed(r, ACK_HOLD_MS)
    }

    private fun scheduleStop(event: PetEvent?) {
        cancelPendingStop()
        val r = Runnable {
            pendingStop = null
            celebrate(event)
        }
        pendingStop = r
        handler.postDelayed(r, STOP_GRACE_MS)
    }

    private fun celebrate(event: PetEvent? = null) {
        face(PetView.Face.CELEBRATE)
        // 终态：链会播一遍庆祝动画后自动收回随机链
        work(PetAnimChain.WorkSlot.SUCCESS)
        bubble(event?.text ?: pick(LINE_SUCCESS))
        cancelPendingStop()
        val r = Runnable {
            pendingStop = null
            idle()
        }
        pendingStop = r
        handler.postDelayed(r, CELEBRATE_HOLD_MS)
    }

    private fun error(event: PetEvent? = null) {
        face(PetView.Face.ERROR)
        work(PetAnimChain.WorkSlot.ERROR)
        bubble(event?.text ?: pick(LINE_ERROR))
        cancelPendingStop()
        val r = Runnable {
            pendingStop = null
            idle()
        }
        pendingStop = r
        handler.postDelayed(r, ERROR_HOLD_MS)
    }

    // ------------------------------------------------------------ 输出

    private fun work(slot: PetAnimChain.WorkSlot?) {
        onWork(slot)
    }

    private fun face(face: PetView.Face) {
        current = face
        onFace(face)
    }

    private fun bubble(text: String?) {
        onBubble(text)
    }

    /** 从台词库 [PetLines] 的工作状态档位随机抽一句；空池（理论不出现）返回 null。 */
    private fun pick(categoryId: String): String? {
        val pool = PetLines.lines(appCtx, categoryId)
        if (pool.isEmpty()) return null
        return pool[Random.nextInt(pool.size)]
    }

    private fun cancelPendingStop() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
    }

    private fun cancelPendingAck() {
        pendingAck?.let { handler.removeCallbacks(it) }
        pendingAck = null
    }

    companion object {
        private const val ACK_HOLD_MS = 1_200L
        private const val STOP_GRACE_MS = 1_500L
        private const val CELEBRATE_HOLD_MS = 2_000L
        private const val ERROR_HOLD_MS = 2_500L

        // 台词库里的工作状态档位分类 id（PetLines.CATEGORIES 的 ws_*），
        // 与 /bubble kind 一一对应：user→thinking、pre→working、post→result、
        // waiting→waiting、stop→success、error→error。内置台词 =
        // dsh-pet web assets/config.jsonc workStatusTexts 原文；用户改台词库即换措辞。
        private const val LINE_THINKING = "ws_thinking"
        private const val LINE_WORKING = "ws_working"
        private const val LINE_RESULT = "ws_result"
        private const val LINE_WAITING = "ws_waiting"
        private const val LINE_SUCCESS = "ws_success"
        private const val LINE_ERROR = "ws_error"

        private val Random = java.util.Random()
    }
}
