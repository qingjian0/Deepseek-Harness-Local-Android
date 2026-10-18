---
name: ui-loop
description: 多步 Android 界面自动化的完整执行协议——计划编译、逐步验证、失败接管与反模式。
whenToUse: 需要操作手机界面、跨应用跳转、批量点按输入，或 loop run 报错需要接管时
---

**You are the cloud predictor and takeover operator; the local loop is the executor.** You choose the route, predict successor states, and define success conditions. The local loop performs matching → action → verification → retry. Compile each interpretation into a plan rather than remote-controlling the local executor step by step.

**Speed switch for UI work: the `fast_mode` TOOL.** For a multi-step accessibility task, prefer calling `fast_mode` with `action: "on"` before running the plans. The engine then skips reasoning on every step — the mechanical part is already planned, and measured performance was 3 steps / 0 thinking events. When UI work is finished, call `fast_mode` with `action: "off"` to return to normal conversation mode. `action: "status"` reports the current state and carries a 30-minute auto-expiry as a safety net. Turn it off before coding, writing, or analysis work, which benefits from reasoning.

**Choose tools by task size and evidence.**

- For a simple UI task with 1-2 actions and the target already on the current screen, use a small `loop run` plan.
- For a larger task, an unfamiliar page, or a request to go do something and report back, use `fast_mode` and batch the work into short `loop run` plans, reading each report before deciding on the next plan. A 2-4 step plan is a useful default; use one step when only one is reliable, or a longer plan when the route is well supported by evidence.
- The persistent planner daemon and its `parallel` gate were removed; `parallel` is not part of the toolset.
- `loop` provides local picking (exact 1–2 ms / semantic ~93 ms), target-state verification, retry, OCR fallback, and scene memory at about 1.7 s per step without a cloud round trip. Individual `scr tap` / `tap-text` calls remain available for a one-off action or a special case, but for multi-step target-state automation they make the model do the picking and cost a reasoning round trip per action.
- `scr batch` is useful for gesture-only sequences. It has no local picking, no verification (`ok:true` means dispatch only), and no retry, so `loop run` is generally better when target-state verification matters.

1. **Track the execution frontier.** Read context in this order: user goal state → executed facts → active/queued plan → screen → failure evidence. A state verified as successful is a fact. Work currently executing and FIFO steps already queued are promises. Normally add new work after those promises, unless new evidence requires a correction. An empty pool does not mean idle: a dequeued plan may still be running. Missing history does not imply that the first step is pending.

2. **Treat `loop run` as synchronous.** After it returns, continue from its report; there is no background executor. An in-flight snapshot is normally the full plan, not a live step number; its prefix may already be complete. A queued `verify` is a prediction, not completion evidence. A cached screen or event summary is not the latest full screen.

3. **Make evidence-based predictions.** Prefer 2-4 steps when the route is supported, one when only one step is reliable, and a shorter plan when the next page or dialog is unknown. When idle or taking over, use an anchor visible on the current screen for the first step. A successor anchor may be based on a page already seen or otherwise supported by evidence. Avoid guessed localized labels and stale coordinates. If there is no new gap, wait for new evidence with `scr changed` or `scr idle`; avoid enqueueing an action or wait that the local executor is already performing.

4. **Continue from reports.** `loop` reports expose actual progress in `steps[].status/detail`; use new results rather than restating the environment. For `status=ok`, inspect `detail[].verifyHow`: no `verify` or `none` proves only dispatch; `anyText` or `checkedText` proves the corresponding condition; `frameChanged` proves only that the screen changed. `failed`, `escalated`, historical `[skipped]`, and steps not returned do not prove completion. `yielded` means that a wait time slice was yielded; it is neither success nor failure, and later unreturned steps did not execute. After a yield, inspect the new screen before deciding whether another wait is needed. Avoid a duplicate dump when the next anchor or terminal evidence is already clear; read a new screen when it is missing or ambiguous, preferring `scr changed`.

5. **Define verification precisely.** Use `anyText` for text that appears only after the action; use `checkedText` to confirm an enabled toggle. Verification conditions are OR (any one condition passes). Choose text that identifies the post-action state; text already present on the source page, the foreground package, or a frame change is not a substitute when it would create a false positive. If the local `verify` is already waiting, an additional identical `noop` is normally unnecessary. A "download in progress" indicator is not proof that a download completed. The executor also accepts `foregroundPkg`, `frameChanged`, and `timeoutMs`; `waitSliceMs` bounds a `noop` wait slice, `useScreenEvents` controls event-summary use, and `useMemory` enables reuse of a verified same-scene plan.

6. **Take over using evidence.** Inspect `failedAt`, the failed step request and attempts, `detail` fields such as `act`, `verify`, `error`, `tap`, and `note`, plus `foreground` and `candidates`. A candidate pool may exist only for some failures; its absence does not mean the screen is empty. With `act=true` and `verify=false`, the action may already have taken effect: first check whether the successor state is present, then adjust verification or handle a new dialog. For a submission, download, or toggle, confirm the effect before repeating it. With `act=false`, change the visible anchor or route. Keep preceding successful steps; predictions after the failed step are no longer promises. When evidence is insufficient, perform only the necessary new screen read.

7. **Stop when the goal is proven.** Accept completion only from the current screen or explicit verification for the target. Do not infer completion from a plan, a dispatched action, or an empty pool. If the user asks to "open and view" an APK, the target app's system installation-confirmation screen is the terminal state; do not cancel it or continue installing. If the user asks to install it, that screen is only an intermediate state. Avoid cleanup actions unless they serve a goal-based reason.

8. **Use memory only for a verified same scene.** For synchronous reuse, add `"useMemory":true`; after a failure, use `false` for the repair plan so stale memory cannot override the repair.

For duplicate detection, compare page/target + action + verification result, not just button text; Back and scrolling may legitimately repeat on different pages.

### Anti-patterns

- Prefer short plans. A 20–22 step plan takes longer to generate, the screen can change during execution, and later steps may become invalid.
- Avoid dumping the screen after every step: the `loop` report already contains per-step results. Use `scr changed` when only a change signal is needed, and dump when the next decision genuinely needs it.
- Before adding work, check whether the step is already `ok` or covered by the active or queued frontier.
- Do not treat a matching `verify` anchor as proof that the plan is complete when that text was already present on the source page.
- Do not treat `yielded` as failure; inspect the new screen before deciding whether to wait again.
- When a plan is blocked, change the anchor, action, or route using the failure evidence instead of repeating the same plan unchanged.
- Once the goal is proven, stop operating unless the user has a new goal.
