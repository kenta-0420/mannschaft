import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EmergencyClosurePreview from '~/components/reservation/emergency-closure/EmergencyClosurePreview.vue'
import type { ClosurePreviewItem } from '~/composables/useEmergencyClosureApi'

/**
 * EmergencyClosurePreview.vue 患者名列 ユニットテスト（CMP-261030-0650）。
 *
 * 観点:
 *   AC-9:   BE の実応答（userFullName）の氏名が表に表示される
 *   AC-10a: 0 件のとき no_reservations を表示し、表を描画しない
 *   AC-10b: userFullName が "" の行は「不明なユーザー」に置き換わる
 */

/** BE の EmergencyClosurePreviewResponse.AffectedReservation をそのまま JSON 化した行。 */
function beRow(reservationId: number, userFullName: string) {
  return {
    reservationId,
    userId: reservationId,
    userFullName,
    userEmail: 'u@example.com',
    slotDate: '2026-11-01',
    startTime: '09:00:00',
    endTime: '10:00:00',
    status: 'CONFIRMED',
  }
}

async function mountPreview(items: unknown[]) {
  return mountSuspended(EmergencyClosurePreview, {
    props: {
      loading: false,
      done: true,
      items: items as unknown as ClosurePreviewItem[],
      formatPreviewDateTime: () => '2026/11/01 09:00-10:00',
      statusLabel: (s: string) => s,
      statusSeverity: () => 'info',
    },
  })
}

describe('EmergencyClosurePreview 患者名', () => {
  it('AC-9: userFullName の氏名が表に表示される', async () => {
    const wrapper = await mountPreview([beRow(1, '山田 太郎')])
    expect(wrapper.find('tbody tr td').text()).toBe('山田 太郎')
    wrapper.unmount()
  }, 120000)

  it('AC-10a: 0件なら no_reservations を表示し表を描画しない', async () => {
    const wrapper = await mountPreview([])
    expect(wrapper.text()).toContain('No reservations found for the target period')
    expect(wrapper.find('table').exists()).toBe(false)
    wrapper.unmount()
  }, 120000)

  it('AC-10b: userFullName が空文字の行は「不明なユーザー」になる', async () => {
    const wrapper = await mountPreview([beRow(1, ''), beRow(2, '鈴木 花子')])
    const names = wrapper.findAll('tbody tr').map((tr) => tr.find('td').text())
    expect(names).toEqual(['Unknown user', '鈴木 花子'])
    wrapper.unmount()
  }, 120000)
})
