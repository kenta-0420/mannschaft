import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

/**
 * CMP-261105-1507 欠陥4（i18n ルール違反）の番人。
 *
 * `app/components/chat/` 配下の .vue に、UI へ出る日本語の直書きが残っていないことを固定する。
 * コメント（`//`・`/* *\/`・`<!-- -->`）は対象外。ロケールファイル側の訳の検査は
 * `tests/unit/i18n/locale-no-japanese-residue.spec.ts` が担う。
 */

const CHAT_DIR = resolve(__dirname, '../../../../app/components/chat')
const JAPANESE = /[぀-ヿ一-鿿]/

function listVueFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return listVueFiles(path)
    return name.endsWith('.vue') ? [path] : []
  })
}

/** コメントを空白へ置き換える（行番号を保つため改行は残す）。 */
function stripComments(source: string): string {
  const blank = (m: string) => m.replace(/[^\n]/g, ' ')
  return source
    .replace(/<!--[\s\S]*?-->/g, blank)
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/(^|[^:'"`])\/\/[^\n]*/g, (m, p1: string) => p1 + blank(m.slice(p1.length)))
}

describe('components/chat の日本語直書き', () => {
  it('コメント以外に日本語が残っていない', () => {
    const offenders: string[] = []
    for (const file of listVueFiles(CHAT_DIR)) {
      const lines = stripComments(readFileSync(file, 'utf8')).split('\n')
      lines.forEach((line, i) => {
        if (JAPANESE.test(line)) {
          offenders.push(`${relative(CHAT_DIR, file)}:${i + 1}: ${line.trim()}`)
        }
      })
    }
    expect(offenders).toEqual([])
  })
})
