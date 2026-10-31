import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EmergencyClosureHistory from '~/components/reservation/emergency-closure/EmergencyClosureHistory.vue'
import type { ClosureConfirmationItem, ClosureHistoryItem } from '~/composables/useEmergencyClosureApi'

/**
 * EmergencyClosureHistory.vue 履歴の送信件数タグ ユニットテスト（CMP-261028-2329）。
 *
 * 観点:
 *   AC-3: BE の実応答 `GET /emergency-closures`（sentCount を返す）の行で「3件」が描画される
 *   AC-4: 描画に undefined を含まない
 */

/** BE の EmergencyClosureResponse をそのまま JSON 化した 1 行（notifiedCount は存在しない）。 */
const beRow = {
  id: 10,
  teamId: 1,
  startDate: '2026-11-01',
  endDate: '2026-11-01',
  startTime: null,
  endTime: null,
  reason: '設備点検',
  subject: '臨時休業のお知らせ',
  messageBody: '本文',
  sentCount: 3,
  cancelReservations: false,
  createdBy: 1,
  createdAt: '2026-10-29T10:00:00',
}

describe('EmergencyClosureHistory 送信件数タグ', () => {
  it('AC-3/4: sentCount=3 の行が「3件」と描画され undefined を含まない', async () => {
    const wrapper = await mountSuspended(EmergencyClosureHistory, {
      props: {
        loading: false,
        items: [beRow] as unknown as ClosureHistoryItem[],
        expandedClosureId: null,
        confirmations: [],
        confirmedCount: 0,
        totalCount: 0,
        confirmationsLoading: false,
        connectionState: 'LIVE',
        formatDate: (iso: string) => iso,
      },
    })

    const text = wrapper.text()
    expect(text).toContain('3件')
    expect(text).not.toContain('undefined')
    wrapper.unmount()
  }, 60000)
})

/**
 * 確認状況パネルの患者名列（CMP-261030-0650）。
 *   AC-9:   BE の実応答（userFullName）の氏名が表示される
 *   AC-10c: userFullName が "" の行は「不明なユーザー」に置き換わる
 */
function beConfirmation(userId: number, userFullName: string) {
  return {
    userId,
    userFullName,
    userEmail: `u${userId}@example.com`,
    appointmentAt: '2026-11-01T09:00:00',
    confirmed: false,
    confirmedAt: null,
    reminderSent: false,
  }
}

async function mountExpanded(confirmations: unknown[]) {
  return mountSuspended(EmergencyClosureHistory, {
    props: {
      loading: false,
      items: [beRow] as unknown as ClosureHistoryItem[],
      expandedClosureId: 10,
      confirmations: confirmations as unknown as ClosureConfirmationItem[],
      confirmedCount: 0,
      totalCount: confirmations.length,
      confirmationsLoading: false,
      connectionState: 'LIVE',
      formatDate: (iso: string) => iso,
    },
  })
}

describe('EmergencyClosureHistory 確認状況の患者名', () => {
  it('AC-9: userFullName の氏名が確認状況パネルに表示される', async () => {
    const wrapper = await mountExpanded([beConfirmation(1, '山田 太郎')])
    const names = wrapper.findAll('table table tbody tr').map((tr) => tr.find('td').text())
    expect(names).toEqual(['山田 太郎'])
    wrapper.unmount()
  }, 120000)

  it('AC-10c: userFullName が空文字の行は「不明なユーザー」になる', async () => {
    const wrapper = await mountExpanded([beConfirmation(1, ''), beConfirmation(2, '鈴木 花子')])
    const names = wrapper.findAll('table table tbody tr').map((tr) => tr.find('td').text())
    expect(names).toEqual(['Unknown user', '鈴木 花子'])
    wrapper.unmount()
  }, 120000)
})