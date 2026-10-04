import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import SearchPage from '~/pages/search/index.vue'
import { useAuthStore } from '~/stores/useAuthStore'

// DTO/OpenAPI が提供する現行9種別の応答を固定する。設計上の未実装応答をmockしない。
const { search, notification } = vi.hoisted(() => ({
  search: vi.fn(),
  notification: { error: vi.fn() },
}))
vi.mock('~/composables/useSearchApi', () => ({ useSearchApi: () => ({ search }) }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notification }))

const kinds = ['schedules', 'events', 'reservations', 'shifts', 'safetyChecks', 'queues', 'teams', 'organizations', 'users']
const labels = ['予定', 'イベント', '予約', 'シフト', '安否確認', '順番待ち', 'チーム', '組織', 'ユーザー']
function response(count: number) {
  return {
    data: {
      query: 'native0700', executionTimeMs: 1,
      counts: Object.fromEntries(kinds.map(kind => [kind, count])),
      results: Object.fromEntries(kinds.map(kind => [kind, Array.from({ length: Math.min(count, 10) }, (_, index) => {
        const field = kind === 'users' ? 'fullName' : ['teams', 'organizations'].includes(kind) ? 'name'
          : kind === 'reservations' ? 'purpose' : kind === 'queues' ? 'guestName' : 'title'
        return { id: index + 1, [field]: `${kind}-native0700-${index + 1}` }
      })])),
    },
  }
}

describe('横断検索の現行件数契約', () => {
  beforeEach(() => {
    useAuthStore().user = { id: 700, email: 'unit-search@example.test', fullName: '検索テスト', profileImageUrl: null }
    search.mockReset()
    notification.error.mockClear()
  })

  async function submit() {
    const wrapper = await mountSuspended(SearchPage, { route: '/search' })
    await wrapper.find('input').setValue('native0700')
    const button = wrapper.findAll('button').find(item => item.text() === '検索')
    expect(button).toBeDefined()
    await button!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('9種別それぞれの総数11と先頭10件を表示する', async () => {
    search.mockResolvedValue(response(11))
    const wrapper = await submit()
    for (let index = 0; index < kinds.length; index++) {
      expect(wrapper.text()).toContain(`${labels[index]} (11)`)
      expect(wrapper.text()).toContain(`${kinds[index]}-native0700-10`)
      expect(wrapper.text()).not.toContain(`${kinds[index]}-native0700-11`)
    }
    expect(notification.error).not.toHaveBeenCalled()
  })

  it('総数0の成功応答は空状態で表示する', async () => {
    search.mockResolvedValue(response(0))
    const wrapper = await submit()
    expect(wrapper.text()).toContain('検索結果がありません')
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(notification.error).not.toHaveBeenCalled()
  })

  it('検索失敗は空状態と区別し再試行で同じ検索を行う', async () => {
    search.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(response(11))
    const wrapper = await submit()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('検索結果がありません')
    await wrapper.find('[data-testid="load-error-state"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('予定 (11)')
    expect(search).toHaveBeenCalledTimes(2)
  })
})
