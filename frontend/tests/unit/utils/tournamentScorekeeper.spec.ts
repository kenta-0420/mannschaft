import { describe, it, expect } from 'vitest'
import { parseUserIdInput, isAlreadyScorekeeper } from '~/utils/tournamentScorekeeper'
import type { ScorekeeperResponse } from '~/types/tournament'

/**
 * F08.7 順位UI Wave B-3: スコアキーパー UI 純関数ヘルパーのユニットテスト。
 */

function sk(userId: number, id = `sk-${userId}`): ScorekeeperResponse {
  return {
    id,
    tournamentId: 100,
    userId,
    createdBy: 1,
    createdAt: '2026-06-12T00:00:00',
  }
}

describe('parseUserIdInput', () => {
  it('SK-UTIL-001: 正の整数文字列を数値へパースする', () => {
    expect(parseUserIdInput('12345')).toBe(12345)
    expect(parseUserIdInput('1')).toBe(1)
  })

  it('SK-UTIL-002: 前後空白を許容してトリムする', () => {
    expect(parseUserIdInput('  42  ')).toBe(42)
  })

  it('SK-UTIL-003: 数値型もそのまま受け入れる', () => {
    expect(parseUserIdInput(77)).toBe(77)
  })

  it('SK-UTIL-004: 空・null・undefined は null', () => {
    expect(parseUserIdInput('')).toBeNull()
    expect(parseUserIdInput('   ')).toBeNull()
    expect(parseUserIdInput(null)).toBeNull()
    expect(parseUserIdInput(undefined)).toBeNull()
  })

  it('SK-UTIL-005: 0 以下・小数・非数値は null', () => {
    expect(parseUserIdInput('0')).toBeNull()
    expect(parseUserIdInput('-5')).toBeNull()
    expect(parseUserIdInput('1.5')).toBeNull()
    expect(parseUserIdInput('abc')).toBeNull()
    expect(parseUserIdInput('12a')).toBeNull()
  })
})

describe('isAlreadyScorekeeper', () => {
  it('SK-UTIL-010: 既存 userId に一致すれば true', () => {
    expect(isAlreadyScorekeeper([sk(1), sk(2)], 2)).toBe(true)
  })

  it('SK-UTIL-011: 未登録 userId なら false', () => {
    expect(isAlreadyScorekeeper([sk(1), sk(2)], 99)).toBe(false)
  })

  it('SK-UTIL-012: 空配列なら常に false', () => {
    expect(isAlreadyScorekeeper([], 1)).toBe(false)
  })
})
