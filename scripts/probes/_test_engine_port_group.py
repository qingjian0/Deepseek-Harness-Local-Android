#!/usr/bin/env python3
"""Guard the fork's single persisted local port-group contract."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENG = ROOT / "app/src/main/java/app/dsh/mobile/engine"
APP = ROOT / "app/src/main/java/app/dsh/mobile"
ASSETS = ROOT / "app/src/main/assets"

manager = (ENG / "EnginePortManager.kt").read_text(encoding="utf-8")
supervisor = (ENG / "EngineSupervisor.kt").read_text(encoding="utf-8")
config = (ENG / "EngineConfig.kt").read_text(encoding="utf-8")
bridge = (ENG / "AgentBridge.kt").read_text(encoding="utf-8")
lan = (ENG / "LanWebProxy.kt").read_text(encoding="utf-8")
remote = (ENG / "RemoteAccessServer.kt").read_text(encoding="utf-8")
picker = (ENG / "FastPicker.kt").read_text(encoding="utf-8")
service = (APP / "service/EngineService.kt").read_text(encoding="utf-8")

checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

# Exact group: base, base+2, +3, +5, +10, +11; whole-group step +1000.
check("default base is 3080", "DEFAULT_BASE = 3080" in manager)
check("whole group advances by 1000", "GROUP_STEP = 1000" in manager)
check("all six offsets are explicit", all(x in manager for x in (
    "web = base", "companion = base + 2", "bridge = base + 3",
    "embedding = base + 5", "lanWeb = base + 10", "remoteAccess = base + 11",
)))
check("availability probe binds all sockets together", (
    "socket.bind(InetSocketAddress(loopback, port), 1)" in manager
    and "held += socket" in manager
    and "held.forEach" in manager
))
check("probe never kills unknown processes", "Process.killProcess" not in manager and "pkill" not in manager)
check("real bind conflict rotates the full group", "fun rotateAfterBindConflict(context: Context)" in manager and "candidate = active.web + EnginePortPlan.GROUP_STEP" in manager)
check("bind race rotates without reclaiming foreign listener", (
    "val bindConflict = looksLikePortConflict() && withContext(Dispatchers.IO) { portInUse() }" in supervisor
    and supervisor.find("if (bindConflict)") < supervisor.find("val portEvidence")
    and "rotateAfterBindConflict(ctx)" in supervisor
))
check("port rotation rebinds app-owned companion listeners", all(x in supervisor for x in (
    "AgentBridge.stop()", "AgentBridge.start(ctx)", "RemoteAccessServer.stop()",
    "RemoteAccessServer.start(ctx)",
)))

# Startup ordering is essential: load persisted base -> reap -> select -> bind app services.
i_load = supervisor.find("EnginePortManager.load(ctx)")
i_reap = supervisor.find("val reaped = OrphanReaper.reap(ctx)")
i_select = supervisor.find("EnginePortManager.select(ctx)")
i_bridge = supervisor.find("AgentBridge.start(ctx)")
check("load persisted plan before orphan reaper", i_load >= 0 and i_reap > i_load)
check("select free group after orphan reaper", i_select > i_reap)
check("select group before AgentBridge binds", i_bridge > i_select)

# All service consumers use the same selected plan.
check("main web port is dynamic", "DEFAULT_PORT: Int get() = EnginePortManager.current().web" in config)
check("webserver prioritizes injected PORT", "Number(process.env.PORT || ctx.webStartup.port || 3080)" in config)
check("all port env vars are injected", all(x in config for x in (
    "DSH_WEB_PORT=", "DSH_COMPANION_PORT=", "DSH_BRIDGE_PORT=",
    "DSH_EMBEDDING_PORT=", "DSH_LAN_WEB_PORT=", "DSH_REMOTE_ACCESS_PORT=",
)))
check("AgentBridge uses selected bridge port", "PORT: Int get() = EnginePortManager.current().bridge" in bridge)
check("Embedding uses selected embedding port", "PORT: Int get() = EnginePortManager.current().embedding" in picker)
check("LAN proxy uses selected LAN and upstream ports", (
    "PORT: Int get() = EnginePortManager.current().lanWeb" in lan
    and "EnginePortManager.current().web" in lan
    and '--upstream' in lan
))
check("remote access uses selected remote port", "PORT: Int get() = EnginePortManager.current().remoteAccess" in remote)
check("remote access toggle defers until port plan selected", "EnginePortManager.isSelected()" in remote)

# LAN proxy must not race the port selection during foreground-service startup.
check("EngineService does not start LAN proxy before selection", "LanWebProxy.start(" not in service)
check("EngineSupervisor starts LAN proxy after healthy boot", (
    "if (healthy)" in supervisor and "LanWebProxy.start(lanPassword)" in supervisor
    and supervisor.find("LanWebProxy.start(lanPassword)") > supervisor.find("if (healthy)")
))
check("manual LAN start defers until plan selected", "EnginePortManager.isSelected()" in lan)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS " if ok else "FAIL ") + name)
print(f"\n{len(checks) - len(failed)} passed, {len(failed)} failed, {len(checks)} total")
raise SystemExit(1 if failed else 0)
