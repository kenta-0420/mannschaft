import { describe, it, expect } from 'vitest'
import {
  applyConfirmationUpdate,
  confirmationsTopicDestination,
  parseConfirmationUpdate,
  type EmergencyClosureConfirmationUpdate,
} from '~/composables/useEmergencyClosureLive'
import type { ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'

/**
 * F03.4+ 臨時休業 確認状況リアルタイム購読の差分適用ロジック ユニットテスト。
 *
 * 観点:
 *   SPEC-001: 該当 userId の行を confirmed=true ＋ confirmedAt セットへ更新（純関数・新配列）
 *   SPEC-002: 既に confirmed の行への再適用は冪等（同じ結果になる・順序逆転/再送に強い）
 *   SPEC-003: スナップショットに存在しない userId は無視（他の行は不変）
 *   SPEC-004: applyConfirmationUpdate は入力配列を破壊しない
 *   SPEC-005: parseConfirmationUpdate は不正フレーム（非 JSON / 必須欠落）を null にする
 *   SPEC-006: parseConfirmationUpdate は正常フレームを型へ載せる
 *   SPEC-007: confirmationsTopicDestination は BE 配信先・購読認可 Interceptor と一致（数値 teamId/closureId）
 */

function item(userId: number, confirmed: boolean): ClosureConfirmationItem {
  return {
    userId,
    userDisplayName: `User ${userId}`,
    userEmail: `u${userId}@example.com`,
    appointmentAt: '2026-06-20T09:00:00',
    confirmed,
    confirmedAt: confirmed ? '2026-06-19T10:00:00' : null,
    reminderSent: false,
  }
}

function update(over: Partial<EmergencyClosureConfirmationUpdate> & { userId: number }): EmergencyClosureConfirmationUpdate {
  return {
    confirmedCount: 1,
    totalCount: 3,
    userFullName: '山田 太郎',
    confirmedAt: '2026-06-19T11:30:00',
    ...over,
  }
}

describe('applyConfirmationUpdate — 確認差分の置換適用', () => {
  it('SPEC-001: 該当 userId の行を confirmed=true ＋ confirmedAt へ更新', () => {
    const items = [item(1, false), item(2, false), item(3, false)]
    const next = applyConfirmationUpdate(items, update({ userId: 2, confirmedAt: '2026-06-19T11:30:00' }))
    expect(next.find(i => i.userId === 2)?.confirmed).toBe(true)
    expect(next.find(i => i.userId === 2)?.confirmedAt).toBe('2026-06-19T11:30:00')
    // 他の行は不変。
    expect(next.find(i => i.userId === 1)?.confirmed).toBe(false)
    expect(next.find(i => i.userId === 3)?.confirmed).toBe(false)
  })

  it('SPEC-002: 既に confirmed の行への再適用は冪等', () => {
    const items = [item(1, true)]
    const next = applyConfirmationUpdate(items, update({ userId: 1, confirmedAt: '2026-06-19T11:30:00' }))
    expect(next[0]?.confirmed).toBe(true)
    expect(next[0]?.confirmedAt).toBe('2026-06-19T11:30:00')
  })

  it('SPEC-003: スナップショットに無い userId は他の行を変えない', () => {
    const items = [item(1, false), item(2, false)]
    const next = applyConfirmationUpdate(items, update({ userId: 99 }))
    expect(next).toEqual(items)
  })

  it('SPEC-004: 入力配列を破壊しない（純関数）', () => {
    const items = [item(1, false)]
    const snapshot = JSON.parse(JSON.stringify(items))
    applyConfirmationUpdate(items, update({ userId: 1 }))
    expect(items).toEqual(snapshot)
  })
})

describe('parseConfirmationUpdate — 受信本文の境界載せ替え', () => {
  it('SPEC-005: 不正フレームは null', () => {
    expect(parseConfirmationUpdate('not json')).toBeNull()
    expect(parseConfirmationUpdate('null')).toBeNull()
    // userId 欠落。
    expect(parseConfirmationUpdate('{"confirmedCount":1,"totalCount":3}')).toBeNull()
    // confirmedCount が文字列。
    expect(parseConfirmationUpdate('{"userId":1,"confirmedCount":"1","totalCount":3}')).toBeNull()
  })

  it('SPEC-006: 正常フレームを型へ載せる', () => {
    const body = JSON.stringify({
      confirmedCount: 2,
      totalCount: 3,
      userId: 7,
      userFullName: '佐藤 花子',
      confirmedAt: '2026-06-19T12:00:00',
    })
    const parsed = parseConfirmationUpdate(body)
    expect(parsed).not.toBeNull()
    expect(parsed?.confirmedCount).toBe(2)
    expect(parsed?.totalCount).toBe(3)
    expect(parsed?.userId).toBe(7)
  })
})

describe('confirmationsTopicDestination — BE 配信先と一致', () => {
  it('SPEC-007: 数値 teamId / closureId のトピック宛先を生成', () => {
    expect(confirmationsTopicDestination(42, 100)).toBe(
      '/topic/teams/42/emergency-closures/100/confirmations',
    )
  })
})
