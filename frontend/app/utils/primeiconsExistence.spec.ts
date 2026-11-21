// @vitest-environment node
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * app 内で使う `pi pi-*` クラスが primeicons.css に実在することの番人。
 * 実在しない名前は無言でアイコンが欠落する（例: pi-bullhorn → pi-megaphone）。
 * 抽出対象は `pi pi-xxx` と引用符付きの単独 'pi-xxx'。`pi ${...}` 等の動的組み立ては対象外。
 * 既知の未対応は KNOWN_MISSING に退避（棚卸し用。増やさず、直したら削る）。
 */
const KNOWN_MISSING = new Set<string>()

const appDir = resolve(__dirname, '..')
const cssPath = resolve(appDir, '../node_modules/primeicons/primeicons.css')
const css = readFileSync(cssPath, 'utf8')
const defined = new Set([...css.matchAll(/\.pi-([a-z0-9-]+)/g)].map((m) => m[1]))

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (name === 'generated') continue
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.(vue|ts)$/.test(name) && !/\.spec\.ts$/.test(name)) out.push(p)
  }
  return out
}

describe('primeicons 実在検査', () => {
  it('primeicons.css が読める', () => {
    expect(existsSync(cssPath)).toBe(true)
    expect(defined.has('megaphone')).toBe(true)
  })
  it('使用中の pi-* が primeicons に存在する（既知の未対応を除く）', () => {
    const used = new Map<string, string>()
    for (const f of walk(appDir)) {
      for (const m of readFileSync(f, 'utf8').matchAll(/(?<![\w-])(?:pi pi-|['"`]pi-)([a-z0-9-]+)(?=['"` ])/g)) {
        if (!used.has(m[1]!)) used.set(m[1]!, f)
      }
    }
    const missing = [...used].filter(([n]) => !defined.has(n) && !KNOWN_MISSING.has(n))
    expect(missing).toEqual([])
  })
})
