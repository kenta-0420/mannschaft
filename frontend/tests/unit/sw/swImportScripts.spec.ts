// @vitest-environment node
/**
 * CMP-261029-0620: SW の読み込み経路の静的試験。
 *
 * @vite-pwa/nuxt（generateSW）が生成する sw.js は、workbox.importScripts に挙げたファイルしか取り込まない。
 * 指定が無いと public/sw-custom.js の push / notificationclick は本番で一度も動かない（今回の根因の一つ）。
 */
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const FRONTEND = resolve(__dirname, '../../..')
const nuxtConfig = readFileSync(resolve(FRONTEND, 'nuxt.config.ts'), 'utf8')

function importScriptsEntries(): string[] {
  const m = nuxtConfig.match(/importScripts\s*:\s*\[([^\]]*)\]/)
  if (!m) return []
  return [...m[1]!.matchAll(/['"`]([^'"`]+)['"`]/g)].map((x) => x[1]!)
}

describe('CMP-261029-0620 SW の読み込み経路', () => {
  it('AC-14: pwa.workbox.importScripts が /sw-custom.js を含み、public に実体がある', () => {
    expect(importScriptsEntries()).toContain('/sw-custom.js')
    expect(existsSync(resolve(FRONTEND, 'public/sw-custom.js'))).toBe(true)
  })

  it('AC-17: Background Sync の処理は別ファイルに退避され、importScripts には載らない', () => {
    expect(existsSync(resolve(FRONTEND, 'public/sw-background-sync.js'))).toBe(true)
    expect(importScriptsEntries().some((e) => e.includes('background-sync'))).toBe(false)
    const sw = readFileSync(resolve(FRONTEND, 'public/sw-custom.js'), 'utf8')
    expect(sw).not.toMatch(/addEventListener\(\s*['"]sync['"]/)
    expect(sw).not.toMatch(/importScripts\(/)
  })
})
