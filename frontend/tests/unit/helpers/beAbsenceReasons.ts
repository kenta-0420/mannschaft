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

export interface JavaToken {
  kind: 'str' | 'id' | 'p'
  text: string
}

/**
 * Java ソースの単一トークン走査。コメントは捨て、文字列（テキストブロック含む）と
 * char リテラルは1トークンとして丸ごと読み飛ばす。括弧の深さ計算などは必ずこのトークン列の上で行う。
 */
export function tokenizeJava(src: string): JavaToken[] {
  const tokens: JavaToken[] = []
  let i = 0
  while (i < src.length) {
    const c = src[i]!
    if (/\s/.test(c)) {
      i++
    } else if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i++
    } else if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2)
      i = end < 0 ? src.length : end + 2
    } else if (src.startsWith('"""', i)) {
      let j = i + 3
      while (j < src.length && !src.startsWith('"""', j)) j += src[j] === '\\' ? 2 : 1
      tokens.push({ kind: 'str', text: src.slice(i, j + 3) })
      i = j + 3
    } else if (c === '"' || c === "'") {
      let j = i + 1
      while (j < src.length && src[j] !== c) j += src[j] === '\\' ? 2 : 1
      tokens.push({ kind: 'str', text: src.slice(i, j + 1) })
      i = j + 1
    } else if (/[A-Za-z_$]/.test(c)) {
      let j = i + 1
      while (j < src.length && /[\w$]/.test(src[j]!)) j++
      tokens.push({ kind: 'id', text: src.slice(i, j) })
      i = j
    } else {
      tokens.push({ kind: 'p', text: c })
      i++
    }
  }
  return tokens
}

const OPEN = new Set(['(', '{', '['])
const CLOSE = new Set([')', '}', ']'])

/**
 * Java ソースから指定 enum の定数名を抜き出す。
 * 定数宣言部（最初のトップレベル `;`、無ければ本体の閉じ `}` まで）を、
 * 深さ0のカンマで分割し、各要素の先頭の識別子（注釈は読み飛ばす）を定数名とする。
 */
export function parseJavaEnumConstants(source: string, enumName: string): string[] {
  const tokens = tokenizeJava(source)
  let i = tokens.findIndex(
    (t, k) => t.kind === 'id' && t.text === 'enum' && tokens[k + 1]?.text === enumName,
  )
  if (i < 0) return []
  while (i < tokens.length && tokens[i]!.text !== '{') i++
  i++

  const elements: JavaToken[][] = [[]]
  let depth = 0
  for (; i < tokens.length; i++) {
    const t = tokens[i]!
    if (t.kind === 'p') {
      if (depth === 0 && (t.text === ';' || t.text === '}')) break
      if (depth === 0 && t.text === ',') {
        elements.push([])
        continue
      }
      if (OPEN.has(t.text)) depth++
      else if (CLOSE.has(t.text)) depth--
    }
    elements[elements.length - 1]!.push(t)
  }

  const names: string[] = []
  for (const el of elements) {
    let k = 0
    while (el[k]?.text === '@') {
      k++
      while (el[k]?.kind === 'id' && el[k + 1]?.text === '.') k += 2
      k++ // 注釈名
      if (el[k]?.text === '(') {
        let d = 0
        for (; k < el.length; k++) {
          if (el[k]!.kind !== 'p') continue
          if (el[k]!.text === '(') d++
          else if (el[k]!.text === ')' && --d === 0) break
        }
        k++
      }
    }
    if (el[k]?.kind === 'id') names.push(el[k]!.text)
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
