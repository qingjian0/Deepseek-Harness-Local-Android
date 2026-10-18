export const name = 'dsh-android-env'
export const inject = ['systemPrompt']

export function apply(ctx) {
  // P0 probe: 无配置、无外部依赖，直接注册一个常驻 section
  // 注意：complete 明确为 false。绝不可设置为 true（多于一个 effective complete section 会让 assemble 失败）。
  ctx.effect(() =>
    ctx.systemPrompt.section({
      name: 'android:p0-probe',
      order: 44,
      complete: false, // 绝不改为 true
      text: '[P0 probe] 从本轮开始，每次回复的第一行必须是 "P0-PROBE-OK".'
    })
  )
}
