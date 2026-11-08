import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
import ReservationsPage from './reservations.vue'
import DashboardErrorState from '~/components/DashboardErrorState.vue'

const { getTeam, handleApiError } = vi.hoisted(() => ({
  getTeam: vi.fn(), handleApiError: vi.fn(),
}))
mockNuxtImport('useTeamApi', () => () => ({ getTeam }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'berlin' } }))

const Panel = defineComponent({
  props: { teamId: String, teamTimezone: String },
  template: '<div data-testid="reservation-panel" />',
})
async function mountPage() {
  return mountSuspended(ReservationsPage, {
    global: { stubs: { TeamReservationsPanel: Panel } },
  })
}

beforeAll(async () => {
  getTeam.mockResolvedValue({ data: { timezone: 'Europe/Berlin' } })
  const wrapper = await mountPage()
  wrapper.unmount()
})
beforeEach(() => {
  getTeam.mockReset()
  handleApiError.mockClear()
})

describe('独立した予約管理ページ', () => {
  it('チーム取得成功後にだけそのtimezoneで予約パネルを表示する', async () => {
    let resolveTeam!: (value: { data: { timezone: string } }) => void
    getTeam.mockReturnValue(new Promise((resolve) => { resolveTeam = resolve }))
    const wrapper = await mountPage()
    expect(wrapper.findComponent(Panel).exists()).toBe(false)

    resolveTeam({ data: { timezone: 'Pacific/Honolulu' } })
    await flushPromises()
    expect(getTeam).toHaveBeenCalledWith('berlin')
    expect(wrapper.findComponent(Panel).props()).toMatchObject({
      teamId: 'berlin', teamTimezone: 'Pacific/Honolulu',
    })
    expect(wrapper.get('a[href="/teams/berlin/admin"]').attributes('href')).toBe('/teams/berlin/admin')
  })

  it('取得失敗を空状態へ倒さず同じ取得処理で再試行する', async () => {
    const failure = new Error('team fetch failed')
    getTeam.mockRejectedValueOnce(failure).mockResolvedValueOnce({ data: { timezone: 'Europe/Berlin' } })
    const wrapper = await mountPage()
    await flushPromises()
    expect(wrapper.findComponent(Panel).exists()).toBe(false)
    expect(wrapper.findComponent(DashboardErrorState).exists()).toBe(true)
    expect(handleApiError).toHaveBeenCalledWith(failure)

    wrapper.findComponent(DashboardErrorState).vm.$emit('retry')
    await flushPromises()
    expect(getTeam).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(DashboardErrorState).exists()).toBe(false)
    expect(wrapper.findComponent(Panel).props('teamTimezone')).toBe('Europe/Berlin')
  })
})
