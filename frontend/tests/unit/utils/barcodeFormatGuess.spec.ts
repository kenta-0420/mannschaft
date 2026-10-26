import { describe, it, expect } from 'vitest'
import {
  isValidEan13,
  isValidEan8,
  guessBarcodeFormat,
} from '~/utils/barcodeFormatGuess'

/**
 * barcodeFormatGuess ユーティリティのユニットテスト。
 *
 * 根治バグ: 桁数のみで EAN13/EAN8 を推測していた旧実装は、
 * GS1 チェックディジットが不正な値（会員カードの任意13桁番号等）を
 * EAN13 と誤推測し JsBarcode の描画失敗を引き起こしていた。
 */

// =====================================================================
// isValidEan13
// =====================================================================

describe('isValidEan13', () => {
  it('チェックディジットが正しい EAN-13 を true と判定する', () => {
    // 1234567890128: d1..d12=123456789012, sum=1×1+2×3+3×1+4×3+5×1+6×3+7×1+8×3+9×1+0×3+1×1+2×3
    //   = 1+6+3+12+5+18+7+24+9+0+1+6 = 92 → check=(10-2)%10=8 → d13=8 ✓
    expect(isValidEan13('1234567890128')).toBe(true)
    // 4901234567894: 日本の実在 JAN コードと同一フォーマット検証済み
    expect(isValidEan13('4901234567894')).toBe(true)
  })

  it('チェックディジットが不正な EAN-13 を false と判定する（バグ再現ケース）', () => {
    // 1234567890123: d13=3 だがアルゴリズム上の正解は 8 → 無効
    // このケースが旧実装では EAN13 と誤推測され描画失敗を起こしていた
    expect(isValidEan13('1234567890123')).toBe(false)
    expect(isValidEan13('4901234567890')).toBe(false)
  })

  it('13桁数字でない入力を false と判定する', () => {
    expect(isValidEan13('')).toBe(false)
    expect(isValidEan13('123456789012')).toBe(false)  // 12桁
    expect(isValidEan13('12345678901234')).toBe(false) // 14桁
    expect(isValidEan13('1234567890ABC')).toBe(false)  // 英字含む
  })
})

// =====================================================================
// isValidEan8
// =====================================================================

describe('isValidEan8', () => {
  it('チェックディジットが正しい EAN-8 を true と判定する', () => {
    // 12345670: d1..d7=1234567, sum=1×3+2×1+3×3+4×1+5×3+6×1+7×3
    //   = 3+2+9+4+15+6+21 = 60 → check=(10-0)%10=0 → d8=0 ✓
    expect(isValidEan8('12345670')).toBe(true)
  })

  it('チェックディジットが不正な EAN-8 を false と判定する', () => {
    expect(isValidEan8('12345671')).toBe(false)
    expect(isValidEan8('12345679')).toBe(false)
  })

  it('8桁数字でない入力を false と判定する', () => {
    expect(isValidEan8('')).toBe(false)
    expect(isValidEan8('1234567')).toBe(false)   // 7桁
    expect(isValidEan8('123456789')).toBe(false)  // 9桁
    expect(isValidEan8('1234ABC0')).toBe(false)   // 英字含む
  })
})

// =====================================================================
// guessBarcodeFormat
// =====================================================================

describe('guessBarcodeFormat', () => {
  it('有効な EAN-13 → "EAN13" を返す', () => {
    expect(guessBarcodeFormat('1234567890128')).toBe('EAN13')
    expect(guessBarcodeFormat('4901234567894')).toBe('EAN13')
  })

  it('有効な EAN-8 → "EAN8" を返す', () => {
    expect(guessBarcodeFormat('12345670')).toBe('EAN8')
  })

  it('チェックディジット不正な13桁数字 → "CODE128" を返す（バグ再現ケース）', () => {
    // このケースが旧実装でバグを引き起こしていた。根治後は CODE128 にフォールバックする
    expect(guessBarcodeFormat('1234567890123')).toBe('CODE128')
  })

  it('英数字混在 → "CODE128" を返す', () => {
    expect(guessBarcodeFormat('ABC123')).toBe('CODE128')
    expect(guessBarcodeFormat('member-12345')).toBe('CODE128')
  })

  it('空文字列 → "CODE128" を返す', () => {
    expect(guessBarcodeFormat('')).toBe('CODE128')
  })

  it('EAN13/EAN8 以外の純粋数字 → "CODE128" を返す', () => {
    expect(guessBarcodeFormat('123456')).toBe('CODE128')      // 6桁
    expect(guessBarcodeFormat('1234567890')).toBe('CODE128')  // 10桁
    expect(guessBarcodeFormat('12345678901234')).toBe('CODE128') // 14桁
  })
})
