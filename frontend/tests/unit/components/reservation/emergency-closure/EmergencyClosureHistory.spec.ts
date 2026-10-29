import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EmergencyClosureHistory from '~/components/reservation/emergency-closure/EmergencyClosureHistory.vue'
import type { ClosureHistoryItem } from '~/composables/useEmergencyClosureApi'

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