import { useNuxtApp } from '#app'
import { beforeAll, describe, expect, it } from 'vitest'
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
const fieldLabels = [
  ['出欠確認する', '終日', '履歴:', '開始日', '開始時刻', '終了日', '終了時刻', '場所'],
  ['Request attendance', 'All day', 'Recent times:', 'Start date', 'Start time', 'End date', 'End time', 'Location'],
  ['确认出勤', '全天', '最近时间：', '开始日期', '开始时间', '结束日期', '结束时间', '地点'],
  ['출석 확인', '종일', '최근 시간:', '시작 날짜', '시작 시간', '종료 날짜', '종료 시간', '장소'],
  ['Solicitar asistencia', 'Todo el día', 'Horas recientes:', 'Fecha de inicio', 'Hora de inicio', 'Fecha de fin', 'Hora de fin', 'Lugar'],
  ['Teilnahme abfragen', 'Ganztägig', 'Letzte Zeiten:', 'Startdatum', 'Startzeit', 'Enddatum', 'Endzeit', 'Ort'],
]
const placeholders = ['場所（任意）', 'Location (optional)', '地点（可选）', '장소 (선택 사항)', 'Lugar (opcional)', 'Ort (optional)']
beforeAll(async () => {
  await Promise.all(locales.map(locale => useNuxtApp().$i18n.loadLocaleMessages(locale)))
})

describe('予定周辺の実翻訳と表示中切替（CMP-261017-1629）', () => {
  it.each(locales)('%s: 基本入力・履歴・作成先が表示中切替へ追従し、終日の入力条件とpayloadを保つ', async (targetLocale) => {
    const form = {
      title: '変更しない題名', location: '', allDay: false, attendanceRequired: true,
      startDate: new Date('2026-10-17T00:00:00Z'), endDate: new Date('2026-10-17T00:00:00Z'),
      startTime: '09:00', endTime: '10:00',
    } as ScheduleEventFormState
    const wrapper = await mountSuspended(ScheduleEventBasicFields, {
      props: { form, fieldErrors: {}, isPersonalScope: false,
        timeHistory: [{ startTime: '11:00', endTime: '12:00' }], timeOptions: [] },
      // カレンダー内部の描画は実機で検証する。本テストは製品側の翻訳・終日分岐を実描画する。
      global: { stubs: {
        DatePicker: { name: 'DatePicker', template: '<input />' },
        Select: { name: 'Select', template: '<select />' },
      } },
    })
    const selector = await mountSuspended(ScheduleEventScopeSelector, {
      props: { selectedScopeKey: 'TEAM:a', scopeOptions: [
        { label: 'A', value: 'TEAM:a', scopeType: 'team', scopeId: 'a', isPersonal: false },
        { label: 'B', value: 'TEAM:b', scopeType: 'team', scopeId: 'b', isPersonal: false },
      ] },
    })
    for (const locale of [targetLocale === 'en' ? 'de' : 'en', targetLocale] as const) {
      const i = locales.indexOf(locale)
      await wrapper.vm.$i18n.setLocale(locale)
      await flushPromises()
      expect(wrapper.text()).toContain(titles[i])
      expect(selector.text()).toContain(scopeLabels[i])
      for (const label of fieldLabels[i]!) expect(wrapper.text()).toContain(label)
      expect(wrapper.get('input[placeholder]').attributes('placeholder')).toBe(placeholders[i])
      expect(wrapper.text()).not.toContain('schedule.form.')
      if (locale !== 'ja') expect(wrapper.text()).not.toMatch(/出欠確認する|履歴:|開始日|開始時刻|終了日|終了時刻/)
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

  it.each(locales)('%s: 曜日と出欠ラベルが表示中localeへ追従し、本人TZの暦日を維持する', async (targetLocale) => {
    const wrapper = await mountSuspended(ScheduleListRow, {
      props: { scopeType: 'team', scopeId: 'team-a', event: {
        id: 1, uniqueKey: '1', title: '練習', startAt: '2026-08-05T01:00:00+09:00',
        endAt: '2026-08-05T02:00:00+09:00', allDay: false, color: null, isPersonal: false,
        attendanceRequired: true, myAttendance: null,
      } },
    })
    for (const locale of [targetLocale === 'en' ? 'de' : 'en', targetLocale] as const) {
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
