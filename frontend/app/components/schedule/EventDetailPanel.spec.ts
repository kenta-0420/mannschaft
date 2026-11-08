import { useNuxtApp } from '#app'
import { beforeAll, describe, expect, it, vi } from 'vitest'
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

// 表示の追従を検査するため、辞書のcoldロードはhookで完了させる。
// 実際の言語切替と描画は各case内で行い、cold切替は実機で検証する。
beforeAll(async () => {
  const i18n = useNuxtApp().$i18n
  await Promise.all((['de', 'en', 'ja', 'zh', 'ko', 'es'] as const).map((locale) => i18n.loadLocaleMessages(locale)))
})

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

describe('予定詳細の状態翻訳（CMP-261017-1629）', () => {
  const labels = {
    ja: ['予定', 'キャンセル', '完了', '下書き', '公開中'],
    en: ['Scheduled', 'Cancelled', 'Completed', 'Draft', 'Published'],
    zh: ['已安排', '已取消', '已完成', '草稿', '已发布'],
    ko: ['예정', '취소됨', '완료됨', '초안', '공개됨'],
    es: ['Programado', 'Cancelado', 'Completado', 'Borrador', 'Publicado'],
    de: ['Geplant', 'Abgesagt', 'Abgeschlossen', 'Entwurf', 'Veröffentlicht'],
  }
  it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'] as const)('%s: BE実状態3種・旧2状態の表示中切替と未知状態を維持する', async (locale) => {
    const wrapper = await mountPanel('')
    const initialLocale = locale === 'de' ? 'en' : 'de'
    await wrapper.vm.$i18n.setLocale(initialLocale)
    await wrapper.setProps({ event: { ...event(''), status: 'SCHEDULED' } })
    expect(wrapper.text()).toContain(labels[initialLocale][0])
    // 同じ状態を表示したまま言語だけを変える。
    await wrapper.vm.$i18n.setLocale(locale)
    await flushPromises()
    expect(wrapper.text()).toContain(labels[locale][0])
    for (const [index, status] of ['SCHEDULED', 'CANCELLED', 'COMPLETED', 'DRAFT', 'PUBLISHED'].entries()) {
      await wrapper.setProps({ event: { ...event(''), status } })
      const label = wrapper.vm.$t(`schedule.status.${status.toLowerCase()}`)
      expect(label).not.toBe(`schedule.status.${status.toLowerCase()}`)
      expect(wrapper.text()).toContain(label)
      expect(wrapper.text()).toContain(labels[locale][index])
      expect(wrapper.text()).not.toContain('schedule.status.')
      expect(wrapper.text()).not.toContain(status)
    }
    await wrapper.setProps({ event: { ...event(''), status: 'FUTURE_STATUS' } })
    expect(wrapper.text()).toContain('FUTURE_STATUS')
  })
})
