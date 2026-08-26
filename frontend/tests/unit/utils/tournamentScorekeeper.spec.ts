import { describe, it, expect } from 'vitest'
import {
  parseUserIdInput,
  isAlreadyScorekeeper,
  filterMemberCandidates,
} from '~/utils/tournamentScorekeeper'
import type { ScorekeeperResponse } from '~/types/tournament'
import type { MemberCardListItem } from '~/types/member-card'

/**
 * F08.7 (3): スコアキーパー指名 純関数ヘルパーのユニットテスト。
 *
 * 検証観点:
 *   parseUserIdInput: 正の整数のみ許可（空・非数値・0以下・小数・前後空白付きは null）
 *   isAlreadyScorekeeper: 既指名 userId の検出
 *   filterMemberCandidates: 既指名除外 + userId 重複排除
 */

function sk(userId: number, id = `sk-${userId}`): ScorekeeperResponse {
  return { id, tournamentId: 1, userId, createdBy: 1, createdAt: '2026-06-12T00:00:00' }
}

function card(id: number, userId: number, displayName = `member-${userId}`): MemberCardListItem {
  return {
    id,
    userId,
    cardNumber: `C-${id}`,
    displayName,
    status: 'ACTIVE',
    issuedAt: '2026-01-01T00:00:00',
    lastCheckinAt: null,
    checkinCount: 0,
  }
}

describe('parseUserIdInput', () => {
  it('正の整数を数値に変換する', () => {
    expect(parseUserIdInput('42')).toBe(42)
    expect(parseUserIdInput('1')).toBe(1)
    expect(parseUserIdInput(7)).toBe(7)
  })

  it('空・null・undefined は null', () => {
    expect(parseUserIdInput('')).toBeNull()
    expect(parseUserIdInput('   ')).toBeNull()
    expect(parseUserIdInput(null)).toBeNull()
    expect(parseUserIdInput(undefined)).toBeNull()
  })

  it('非数値・0以下・小数・記号は null（trim 後の整数は許可）', () => {
    expect(parseUserIdInput('abc')).toBeNull()
    expect(parseUserIdInput('0')).toBeNull()
    expect(parseUserIdInput('-3')).toBeNull()
    expect(parseUserIdInput('1.5')).toBeNull()
    expect(parseUserIdInput('1e3')).toBeNull()
    expect(parseUserIdInput(' 5 ')).toBe(5)
  })
})

describe('isAlreadyScorekeeper', () => {
  it('既に指名済みの userId を true で返す', () => {
    const list = [sk(9), sk(10)]
    expect(isAlreadyScorekeeper(list, 9)).toBe(true)
    expect(isAlreadyScorekeeper(list, 10)).toBe(true)
  })

  it('未指名の userId は false', () => {
    expect(isAlreadyScorekeeper([sk(9)], 11)).toBe(false)
    expect(isAlreadyScorekeeper([], 1)).toBe(false)
  })
})

describe('filterMemberCandidates', () => {
  it('既に指名済みのユーザーを候補から除外する', () => {
    const candidates = [card(1, 9), card(2, 10), card(3, 11)]
    const assigned = [sk(9)]
    const result = filterMemberCandidates(candidates, assigned)
    expect(result.map((c) => c.userId)).toEqual([10, 11])
  })

  it('同一 userId の重複会員証は 1 件に絞る（最初の 1 件を残す）', () => {
    const candidates = [card(1, 10), card(2, 10, 'dup'), card(3, 11)]
    const result = filterMemberCandidates(candidates, [])
    expect(result.map((c) => c.id)).toEqual([1, 3])
  })

  it('全て指名済みなら空配列', () => {
    const candidates = [card(1, 9), card(2, 10)]
    const result = filterMemberCandidates(candidates, [sk(9), sk(10)])
    expect(result).toEqual([])
  })

  it('候補が空なら空配列', () => {
    expect(filterMemberCandidates([], [sk(9)])).toEqual([])
  })
})
