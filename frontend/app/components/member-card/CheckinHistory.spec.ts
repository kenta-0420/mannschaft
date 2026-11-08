import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import CheckinHistory from './CheckinHistory.vue'

const getCheckins = vi.hoisted(() => vi.fn())
mockNuxtImport('useMemberCardApi', () => () => ({ getCheckins }))
mockNuxtImport('useRelativeTime', () => () => ({ formatRelative: (value: string) => value }))

describe('CheckinHistory', () => {
  beforeEach(() => getCheckins.mockReset())
  it('BEのスタッフ名を表示し、会員変更時に新しい履歴を取得する', async () => {
    getCheckins.mockImplementation((id: number) => Promise.resolve({ data: [{
      id, checkedInAt: '2026-10-15T12:00:00', checkinType: 'STAFF_SCAN',
      checkedInByName: `スタッフ${id}`, location: '受付', cardNumber: 'M-1', displayName: '会員',
    }] }))
    const wrapper = await mountSuspended(CheckinHistory, { props: { cardId: 1 } })
    await flushPromises()
    expect(wrapper.text()).toContain('スタッフ1')
    await wrapper.setProps({ cardId: 2 })
    await flushPromises()
    expect(getCheckins).toHaveBeenLastCalledWith(2)
    expect(wrapper.text()).toContain('スタッフ2')
    expect(wrapper.text()).not.toContain('スタッフ1')
  })
  it('取得失敗を空履歴と区別し、同じ取得処理で再試行する', async () => {
    getCheckins.mockRejectedValueOnce({ statusCode: 503 }).mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(CheckinHistory, { props: { cardId: 1 } })
    await flushPromises()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(getCheckins).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
  })
})
