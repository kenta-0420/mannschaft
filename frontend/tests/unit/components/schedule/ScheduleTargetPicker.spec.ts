import { defineComponent } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ScheduleTargetPicker from '~/components/schedule/ScheduleTargetPicker.vue'

const { mockApi } = vi.hoisted(() => ({ mockApi: vi.fn() }))

mockNuxtImport('useApi', () => () => mockApi)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const MultiSelectStub = defineComponent({
  props: { options: { type: Array, default: () => [] } },
  template: '<div data-testid="member-options">{{ options.length }}</div>',
})

describe('ScheduleTargetPicker', () => {
  it('501人目以降もtotalPagesまで取得して候補に含める', async () => {
    const firstPage = Array.from({ length: 500 }, (_, index) => ({
      userId: index + 1,
      displayName: `member-${index + 1}`,
      avatarUrl: null,
      calendarColor: null,
    }))
    mockApi
      .mockResolvedValueOnce({ data: firstPage, meta: { totalPages: 2 } })
      .mockResolvedValueOnce({
        data: [{ userId: 501, displayName: 'member-501', avatarUrl: null, calendarColor: null }],
        meta: { totalPages: 2 },
      })

    const wrapper = await mountSuspended(ScheduleTargetPicker, {
      props: {
        scopeType: 'team',
        scopeId: 'family',
        targetMode: 'SELECTED_MEMBERS',
        targetUserIds: [],
      },
      global: {
        stubs: {
          MultiSelect: MultiSelectStub,
          RadioButton: true,
          Avatar: true,
        },
      },
    })
    await flushPromises()

    expect(mockApi).toHaveBeenNthCalledWith(
      1, '/api/v1/teams/family/members?page=0&size=500',
    )
    expect(mockApi).toHaveBeenNthCalledWith(
      2, '/api/v1/teams/family/members?page=1&size=500',
    )
    expect(wrapper.get('[data-testid="member-options"]').text()).toBe('501')
  })
})
