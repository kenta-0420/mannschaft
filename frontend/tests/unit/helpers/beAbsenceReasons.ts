import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * BE の正本 `school/entity/AbsenceReason.java` から enum 定数を抜き出す。
 * 手書きの8値リストをテストに持たないための契約テスト用ヘルパ。
 * 抜き出し件数が0（パス誤り・書式変更）なら例外にして、空集合同士の一致で green になるのを防ぐ。
 */
const BE_ABSENCE_REASON_PATH = resolve(
  __dirname,
  '../../../../backend/src/main/java/com/mannschaft/app/school/entity/AbsenceReason.java',
)

export function readBeAbsenceReasons(): string[] {
  const src = readFileSync(BE_ABSENCE_REASON_PATH, 'utf-8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
  const body = /enum\s+AbsenceReason\s*\{([\s\S]*?)\}/.exec(src)?.[1] ?? ''
  const names = body
    .split(/[,;]/)
    .map((s) => s.trim())
    .filter((s) => /^[A-Z][A-Z0-9_]*$/.test(s))
  if (names.length === 0) {
    throw new Error(`AbsenceReason の enum 定数を抜き出せません: ${BE_ABSENCE_REASON_PATH}`)
  }
  return names
}
