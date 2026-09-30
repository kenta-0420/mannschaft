import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * BE の正本 `school/entity/AbsenceReason.java` から enum 定数を抜き出す。
 * 手書きの値リストをテストに持たないための契約テスト用ヘルパ。
 * 抜き出し件数が0（パス誤り・書式変更）なら例外にして、空集合同士の一致で green になるのを防ぐ。
 */
const BE_ABSENCE_REASON_PATH = resolve(
  __dirname,
  '../../../../backend/src/main/java/com/mannschaft/app/school/entity/AbsenceReason.java',
)

/** コメントを除去する（文字列・文字リテラル内の `//` `/*` は保持する）。 */
export function stripJavaComments(src: string): string {
  let out = ''
  let i = 0
  while (i < src.length) {
    const c = src[i]!
    const n = src[i + 1]
    if (c === '"' || c === "'") {
      let j = i + 1
      while (j < src.length && src[j] !== c) j += src[j] === '\\' ? 2 : 1
      out += src.slice(i, j + 1)
      i = j + 1
    } else if (c === '/' && n === '/') {
      while (i < src.length && src[i] !== '\n') i++
    } else if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2)
      i = end < 0 ? src.length : end + 2
      out += ' '
    } else {
      out += c
      i++
    }
  }
  return out
}

/**
 * Java ソースから指定 enum の定数名を抜き出す。
 * 定数宣言部（最初のトップレベル `;`、無ければ本体の閉じ `}` まで）を、
 * 括弧・波括弧・角括弧の深さ0のカンマで分割し、各要素の先頭の識別子を定数名とする。
 */
export function parseJavaEnumConstants(source: string, enumName: string): string[] {
  const src = stripJavaComments(source)
  const head = new RegExp(`\\benum\\s+${enumName}\\b[^{]*\\{`).exec(src)
  if (!head) return []
  const body = src.slice(head.index + head[0].length)

  const parts: string[] = []
  let depth = 0
  let current = ''
  let i = 0
  scan: while (i < body.length) {
    const c = body[i]!
    if (c === '"' || c === "'") {
      let j = i + 1
      while (j < body.length && body[j] !== c) j += body[j] === '\\' ? 2 : 1
      current += body.slice(i, j + 1)
      i = j + 1
      continue
    }
    if (c === '(' || c === '{' || c === '[') depth++
    else if (c === ')' || c === ']') depth--
    else if (c === '}') {
      if (depth === 0) break scan // enum 本体の終わり
      depth--
    } else if (depth === 0 && c === ';') {
      break scan // 定数宣言部の終わり
    } else if (depth === 0 && c === ',') {
      parts.push(current)
      current = ''
      i++
      continue
    }
    current += c
    i++
  }
  parts.push(current)

  const names: string[] = []
  for (const part of parts) {
    // 先頭の注釈（@Foo / @Foo(...)）を読み飛ばす
    let rest = part.trim()
    for (;;) {
      const ann = /^@[A-Za-z_$][\w$.]*\s*/.exec(rest)
      if (!ann) break
      rest = rest.slice(ann[0].length)
      if (rest.startsWith('(')) {
        let d = 0
        let k = 0
        for (; k < rest.length; k++) {
          if (rest[k] === '(') d++
          else if (rest[k] === ')' && --d === 0) break
        }
        rest = rest.slice(k + 1).trim()
      }
    }
    const m = /^[A-Za-z_$][\w$]*/.exec(rest)
    if (m) names.push(m[0])
  }
  return names
}

export function readBeAbsenceReasons(): string[] {
  const names = parseJavaEnumConstants(readFileSync(BE_ABSENCE_REASON_PATH, 'utf-8'), 'AbsenceReason')
  if (names.length === 0) {
    throw new Error(`AbsenceReason の enum 定数を抜き出せません: ${BE_ABSENCE_REASON_PATH}`)
  }
  return names
}
