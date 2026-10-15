import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import DismissalStatusBadge from '~/components/event/dismissal/DismissalStatusBadge.vue'
import type { DismissalStatusResponse } from '~/types/care'

/**
 * F03.12 Phase10 §16 DismissalStatusBadge.vue のユニットテスト。
 *
 * - dismissed=false なら何も描画しない
 * - dismissed=true なら ✓ 解散済みバッジを描画する
 * - reminderCount>=1 ならリマインド回数チップが追加描画される
 */

function makeStatus(overrides: Partial<DismissalStatusResponse> = {}): DismissalStatusResponse {
  return {
    dismissalNotificationSentAt: null,
    dismissalNotifiedByUserId: null,
    reminderCount: 0,
    lastReminderAt: null,
    dismissed: false,
    ...overrides,
  }
}

describe('DismissalStatusBadge.vue', () => {
  it('dismissed=false なら何も描画しない', async () => {
    const wrapper = await mountSuspended(DismissalStatusBadge, {
      props: { status: makeStatus({ dismissed: false }) },
    })

    expect(wrapper.find('[data-testid="dismissal-status-badge"]').exists()).toBe(false)
  })

  it('dismissed=true ならバッジを描画する', async () => {
    const wrapper = await mountSuspended(DismissalStatusBadge, {
      props: {
        status: makeStatus({
          dismissed: true,
          dismissalNotificationSentAt: '2026-04-27T10:30:00.000Z',
        }),
      },
    })

    const badge = wrapper.find('[data-testid="dismissal-status-badge"]')
    expect(badge.exists()).toBe(true)
  })

  it('reminderCount=0 のときリマインドカウントチップは描画しない', async () => {
    const wrapper = await mountSuspended(DismissalStatusBadge, {
      props: {
        status: makeStatus({
          dismissed: true,
          dismissalNotificationSentAt: '2026-04-27T10:30:00.000Z',
          reminderCount: 0,
        }),
      },
    })

    expect(wrapper.find('[data-testid="dismissal-reminder-count"]').exists()).toBe(false)
  })

  it('reminderCount>=1 のときリマインドカウントチップが描画される', async () => {
    const wrapper = await mountSuspended(DismissalStatusBadge, {
      props: {
        status: makeStatus({
          dismissed: true,
          dismissalNotificationSentAt: '2026-04-27T10:30:00.000Z',
          reminderCount: 2,
        }),
      },
    })

    const chip = wrapper.find('[data-testid="dismissal-reminder-count"]')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toContain('2')
  })
})
