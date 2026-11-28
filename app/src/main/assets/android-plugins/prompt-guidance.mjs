import fs from 'node:fs'
import path from 'node:path'
import '@deepseek-ai/dsh-system-prompt'

export const name = 'android-prompt-guidance'
export const inject = ['systemPrompt']

export function apply(ctx) {
  const home = process.env.DSH_HOME || process.cwd()
  const settingsPath = path.join(home, 'settings', 'prompt-guidance.json')
  let cachedMtime = -1
  let cached = { sessionPrompt: '', toolPrompt: '' }

  const readSettings = () => {
    try {
      const stat = fs.statSync(settingsPath)
      if (stat.mtimeMs !== cachedMtime) {
        const value = JSON.parse(fs.readFileSync(settingsPath, 'utf8'))
        cached = {
          sessionPrompt: typeof value.sessionPrompt === 'string' ? value.sessionPrompt : '',
          toolPrompt: typeof value.toolPrompt === 'string' ? value.toolPrompt : '',
        }
        cachedMtime = stat.mtimeMs
      }
    } catch {
      cached = { sessionPrompt: '', toolPrompt: '' }
      cachedMtime = -1
    }
    return cached
  }

  ctx.systemPrompt.section({
    name: 'android:session-guidance',
    order: 700,
    text: () => readSettings().sessionPrompt,
    interpolate: false,
    replacement: 'The Android session guidance has changed.',
    retirement: 'The Android session guidance no longer applies.',
  })

  ctx.systemPrompt.section({
    name: 'android:tool-guidance',
    order: 9500,
    text: () => readSettings().toolPrompt,
    interpolate: false,
    replacement: 'The Android tool guidance has changed.',
    retirement: 'The Android tool guidance no longer applies.',
  })
}
