import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 番人（CMP-261105-1507）: FE の chat 要求型（types/chat.ts の *Request）は、
 * 同名の生成型（BE の OpenAPI）と「項目名」「型」が一致しなければならない。
 * 経緯: CreateChannelRequest が memberIds / teamId:string を送り、BE の memberUserIds / Long と
 * 食い違って Zimmer 作成が 400 になっていた。
 */
const appDir = resolve(__dirname, '../../../app')
const chatTypes = readFileSync(join(appDir, 'types/chat.ts'), 'utf8')
const generated = readFileSync(join(appDir, 'types/generated/index.ts'), 'utf8')

type Fields = Map<string, string>

/** `{ ... }` の中の「項目名 -> 型」を読む。名前付き型・文字列リテラル型は 'enum' に正規化する */
function parseFields(body: string): Fields {
  const fields: Fields = new Map()
  for (const m of body.matchAll(/^\s*([A-Za-z0-9_]+)\??:\s*([^;\n]+?);?\s*$/gm)) {
    const type = (m[2] ?? '').replace(/\s+/g, '')
    fields.set(m[1] ?? '', /^[A-Z][A-Za-z]+$/.test(type) || type.includes("'") ? 'enum' : type)
  }
  return fields
}

function feRequests(): Map<string, Fields> {
  const map = new Map<string, Fields>()
  for (const m of chatTypes.matchAll(/export interface (\w+Request) \{([\s\S]*?)^\}/gm)) {
    map.set(m[1] ?? '', parseFields(m[2] ?? ''))
  }
  return map
}

function generatedSchema(name: string): Fields | undefined {
  const m = new RegExp(`^ {8}${name}: \\{([\\s\\S]*?)^ {8}\\};`, 'm').exec(generated)
  return m ? parseFields(m[1] ?? '') : undefined
}

describe('chat の要求型は生成型の項目名・型と一致する', () => {
  const requests = [...feRequests()].filter(([name]) => generatedSchema(name))

  it('CreateChannelRequest を検査対象として拾えている', () => {
    expect(requests.map(([n]) => n)).toContain('CreateChannelRequest')
  })

  it.each(requests)('%s', (name, fields) => {
    const gen = generatedSchema(name) as Fields
    for (const [key, type] of fields) {
      expect(gen.has(key), `生成型 ${name} に項目 ${key} が無い`).toBe(true)
      if (type !== 'enum' && gen.get(key) !== 'enum') {
        expect(type, `${name}.${key} の型が生成型と違う`).toBe(gen.get(key))
      }
    }
  })
})
