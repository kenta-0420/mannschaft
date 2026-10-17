import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import EventDetailPanel from './EventDetailPanel.vue'

mockNuxtImport('useDatetime', () => () => ({
  formatDate: (value: string) => value,
  formatDateTime: (value: string) => value,
}))
mockNuxtImport('useMatchOrgContext', () => () => ({
  resolveContext: vi.fn().mockResolvedValue(null),
}))
mockNuxtImport('useMatchApi', () => () => ({
  resolveMatchBySchedule: vi.fn(),
  createMatch: vi.fn(),
}))
mockNuxtImport('useScheduleApi', () => () => ({}))
mockNuxtImport('useNotification', () => () => ({}))
mockNuxtImport('useEventDelegationApi', () => () => ({ fetchDelegations: vi.fn() }))

function event(displayName: string) {
  return {
    id: 1,
    title: '練習',
    description: null,
    location: '広場',
    startAt: '2026-10-17T12:00:00+09:00',
    endAt: '2026-10-17T13:00:00+09:00',
    allDay: false,
    status: 'PUBLISHED',
    categoryName: null,
    categoryColor: null,
    createdBy: { displayName },
    attendanceRequired: false,
    myAttendance: null,
    attendanceStats: null,
  }
}

async function mountPanel(displayName: string) {
  const wrapper = await mountSuspended(EventDetailPanel, {
    props: { event: event(displayName), scopeType: 'team', scopeId: 'team-a', canEdit: false },
    global: { stubs: { ScheduleTargetAudience: true } },
  })
  await wrapper.vm.$i18n.setLocale('de')
  await flushPromises()
  return wrapper
}

describe('予定詳細の作成者翻訳（CMP-261017-0854）', () => {
  it('特殊文字を名前のテキストとして保持し、表示中の言語変更に追従する', async () => {
    const name = '<b>Anna & 李</b>'
    const wrapper = await mountPanel(name)
    expect(wrapper.text()).toContain(`Erstellt von: ${name}`)
    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.text()).toContain('練習')
    expect(wrapper.text()).toContain('広場')

    await wrapper.vm.$i18n.setLocale('ja')
    await flushPromises()
    expect(wrapper.text()).toContain(`作成: ${name}`)
    expect(wrapper.text()).not.toContain('Erstellt von:')
  })

  it('空名も翻訳文へ補間し、runtime nullでは既存の作成者行を表示しない', async () => {
    const wrapper = await mountPanel('')
    expect(wrapper.text()).toContain('Erstellt von:')

    // 型上は必須だが、既存v-ifが防御しているruntime nullも維持する。
    await wrapper.setProps({
      event: { ...event(''), createdBy: null } as unknown as ReturnType<typeof event>,
    })
    expect(wrapper.find('.pi-user').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Erstellt von:')
  })
})
