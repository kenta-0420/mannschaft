import { useNuxtApp } from '#app'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ScheduleListRow from './ScheduleListRow.vue'
import ScheduleEventBasicFields from './event-form/ScheduleEventBasicFields.vue'
import ScheduleEventScopeSelector from './event-form/ScheduleEventScopeSelector.vue'
import type { ScheduleEventFormState } from './event-form/types'

mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('America/Los_Angeles'), formatTime: (s: string) => s }))
mockNuxtImport('useScheduleApi', () => () => ({}))
mockNuxtImport('useNotification', () => () => ({}))
mockNuxtImport('useErrorHandler', () => () => ({}))
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const titles = ['タイトル', 'Title', '标题', '제목', 'Título', 'Titel']
const scopeLabels = ['作成先', 'Create in', '创建到', '생성 위치', 'Crear en', 'Erstellen in']
beforeAll(async () => {
  await Promise.all(locales.map(locale => useNuxtApp().$i18n.loadLocaleMessages(locale)))
})

describe('予定周辺の実翻訳と表示中切替（CMP-261017-1629）', () => {
  it('基本入力・履歴・作成先が6言語へ追従し、終日の入力条件とpayloadを保つ', async () => {
    const form = {
      title: '変更しない題名', location: '', allDay: false, attendanceRequired: true,
      startDate: new Date('2026-10-17T00:00:00Z'), endDate: new Date('2026-10-17T00:00:00Z'),
      startTime: '09:00', endTime: '10:00',
    } as ScheduleEventFormState
    const wrapper = await mountSuspended(ScheduleEventBasicFields, {
      props: { form, fieldErrors: {}, isPersonalScope: false,
        timeHistory: [{ startTime: '11:00', endTime: '12:00' }], timeOptions: [] },
    })
    const selector = await mountSuspended(ScheduleEventScopeSelector, {
      props: { selectedScopeKey: 'TEAM:a', scopeOptions: [
        { label: 'A', value: 'TEAM:a', scopeType: 'team', scopeId: 'a', isPersonal: false },
        { label: 'B', value: 'TEAM:b', scopeType: 'team', scopeId: 'b', isPersonal: false },
      ] },
    })
    for (const [i, locale] of locales.entries()) {
      await wrapper.vm.$i18n.setLocale(locale)
      await flushPromises()
      expect(wrapper.text()).toContain(titles[i])
      expect(selector.text()).toContain(scopeLabels[i])
      expect(wrapper.text()).toContain(wrapper.vm.$t('schedule.form.history'))
      expect(wrapper.get('input[data-testid="schedule-title"]').element).toHaveProperty('value', '変更しない題名')
    }
    await wrapper.setProps({ form: { ...form, allDay: true } })
    expect(wrapper.findAllComponents({ name: 'Select' })).toHaveLength(0)
    expect(wrapper.text()).not.toContain('11:00')
    expect(wrapper.findAllComponents({ name: 'DatePicker' })).toHaveLength(2)
    expect(form.startTime).toBe('09:00')
    expect(form.endTime).toBe('10:00')
  })

  it('曜日と出欠ラベルが表示中localeへ追従し、本人TZの暦日を維持する', async () => {
    const wrapper = await mountSuspended(ScheduleListRow, {
      props: { scopeType: 'team', scopeId: 'team-a', event: {
        id: 1, uniqueKey: '1', title: '練習', startAt: '2026-08-05T01:00:00+09:00',
        endAt: '2026-08-05T02:00:00+09:00', allDay: false, color: null,
        attendanceRequired: true, myAttendance: null,
      } },
    })
    for (const locale of locales) {
      await wrapper.vm.$i18n.setLocale(locale)
      await flushPromises()
      const day = new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'America/Los_Angeles' })
        .format(new Date('2026-08-05T01:00:00+09:00'))
      expect(wrapper.text()).toContain(`8/4 (${day})`)
      expect(wrapper.text()).not.toContain('8/5')
      expect(wrapper.text()).toContain(wrapper.vm.$t('schedule.attendance.yes'))
    }
    await wrapper.setProps({ event: { ...wrapper.props('event'), startAt: '' } })
    expect(wrapper.text()).not.toContain('Invalid')
  })
})
