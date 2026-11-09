import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 番人（CMP-261105-1507）: FE の chat API が呼ぶ URL とメソッドは、BE の OpenAPI から
 * 生成した types/generated に存在しなければならない。
 * 経緯: getOrCreateDm が BE に無い POST /api/v1/chat/channels/dm を呼び、405 になっていた。
 */
const appDir = resolve(__dirname, '../../../app')
const generated = readFileSync(join(appDir, 'types/generated/index.ts'), 'utf8')

/** 生成型の paths から「パス -> メソッド集合」を作る */
function loadGeneratedPaths(): Map<string, Set<string>> {
  const start = generated.indexOf('export interface paths {')
  const end = generated.indexOf('export type webhooks', start)
  const block = generated.slice(start, end === -1 ? undefined : end)
  const map = new Map<string, Set<string>>()
  const re = /^ {4}"(\/api\/v1\/[^"]+)": \{([\s\S]*?)^ {4}\};/gm
  for (const m of block.matchAll(re)) {
    const methods = new Set<string>()
    for (const mm of (m[2] ?? '').matchAll(/^ {8}(get|put|post|delete|patch): (?!never)/gm)) {
      methods.add((mm[1] ?? '').toUpperCase())
    }
    map.set(m[1] ?? '', methods)
  }
  return map
}

/** パスの {x} と ${...} を {} に潰した正規形 */
const normalize = (p: string) => p.replace(/\$\{[^}]*\}/g, '{}').replace(/\{[^}/]*\}/g, '{}')

interface Call {
  file: string
  url: string
  method: string
}

function collectChatCalls(): Call[] {
  const calls: Call[] = []
  const dirs = [join(appDir, 'composables'), join(appDir, 'composables/chat')]
  const files = dirs.flatMap((d) =>
    readdirSync(d)
      .filter((f) => /^useChat.*\.ts$/.test(f))
      .map((f) => join(d, f)),
  )
  for (const file of files) {
    const src = readFileSync(file, 'utf8')
    // api( / api<...>( の第1引数が chat 系 URL のもの。呼び出し直後の { ... } からメソッドを読む
    const re = /\bapi(?:<[^(]*?>)?\(\s*(['"`])(\/api\/v1\/chat[^'"`]*)\1([\s\S]{0,200}?)\)/g
    for (const m of src.matchAll(re)) {
      const url = (m[2] ?? '').split('?')[0] ?? ''
      const method = /method:\s*'(\w+)'/.exec(m[3] ?? '')?.[1]?.toUpperCase() ?? 'GET'
      calls.push({ file: file.replace(appDir, 'app'), url, method })
    }
  }
  return calls
}

describe('chat API の URL は生成型（BE の OpenAPI）に存在する', () => {
  const paths = loadGeneratedPaths()
  const normalized = new Map([...paths].map(([p, ms]) => [normalize(p), ms]))
  const calls = collectChatCalls()

  it('棚卸し対象の呼び出しを十分な数だけ拾えている', () => {
    expect(calls.length).toBeGreaterThan(20)
  })

  it('DM 開始は BE の正規窓口 /conversations を使い、存在しない /dm を呼ばない', () => {
    const urls = calls.map((c) => c.url)
    expect(urls).not.toContain('/api/v1/chat/channels/dm')
    expect(urls).toContain('/api/v1/chat/channels/conversations')
  })

  it.each(calls.map((c) => [`${c.method} ${c.url} (${c.file})`, c] as const))(
    '%s',
    (_label, c) => {
      const methods = normalized.get(normalize(c.url))
      expect(methods, `BE に ${c.url} が無い`).toBeDefined()
      expect(methods?.has(c.method), `BE の ${c.url} は ${c.method} 非対応`).toBe(true)
    },
  )
})
