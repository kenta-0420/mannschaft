import { defineComponent, h, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ScheduleEventForm from '~/components/schedule/ScheduleEventForm.vue'
// dayjs の utc/timezone プラグインを有効化する（本番では app/plugins/dayjs.ts が行う）。
import '~/plugins/dayjs'

/**
 * F03.19 §6.6.5 / AC-21h・AC-22c: 作成ダイアログの時刻プリセット（`initialStartAt` /
 * `initialEndAt`）の受け側検証。
 *
 * 核心は【R16】——受け取った ISO 8601 を **ユーザー設定 TZ の壁時計** として解釈すること。
 * `new Date(value)` は実行端末のローカル TZ で解釈されるため、端末 TZ とユーザー設定 TZ が
 * 異なると時刻がずれる。それを検出するため、本ファイルは **端末 TZ を UTC に固定**した上で
 * ユーザー設定 TZ を `Asia/Tokyo` にして検証する（同じにしてしまうと欠陥が素通りする）。
 */

const scheduleApiMock = {
  createSchedule: vi.fn(),
  createPersonalSchedule: vi.fn(),
  updateSchedule: vi.fn(),
  updatePersonalSchedule: vi.fn(),
  getSchedule: vi.fn(),
  getMyScheduleDetail: vi.fn(),
}
const notificationMock = { success: vi.fn(), error: vi.fn() }
const errorHandlerMock = { handleApiError: vi.fn(), getFieldErrors: vi.fn(() => ({})) }
const googleCalendarMock = { googleSyncEnabled: ref(false), fetchPersonalSyncStatus: vi.fn() }

vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => scheduleApiMock }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => errorHandlerMock }))
vi.mock('~/composables/useGoogleCalendarApi', () => ({ useGoogleCalendarApi: () => googleCalendarMock }))

// ユーザー設定タイムゾーン（useDatetime が useAuthStore から読む値）を Asia/Tokyo に固定する。
const authStoreStub = { user: { timezone: 'Asia/Tokyo' } }
mockNuxtImport('useAuthStore', () => () => authStoreStub)

const DialogStub = defineComponent({
  name: 'Dialog',
  props: { visible: Boolean, header: String },
  emits: ['update:visible', 'hide'],
  setup(_props, { slots }) {
    return () => h('div', { 'data-testid': 'dialog-stub' }, [slots.default?.(), slots.footer?.()])
  },
})

/** フォーム状態のうち日時関連だけを DOM へ露出するスタブ（script setup の内部状態は直接読めないため）。 */
const BasicFieldsStub = defineComponent({
  name: 'ScheduleEventBasicFields',
  props: { form: { type: Object, required: true } },
  setup(props) {
    return () => {
      const f = props.form as { startDate: Date | null, endDate: Date | null, startTime: string, endTime: string }
      const fmt = (d: Date | null) =>
        d
          ? `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
          : ''
      return h('div', {
        'data-testid': 'datetime-probe',
        'data-start-date': fmt(f.startDate),
        'data-end-date': fmt(f.endDate),
        'data-start-time': f.startTime,
        'data-end-time': f.endTime,
      })
    }
  },
})

const globalStubs = {
  Dialog: DialogStub,
  ScheduleEventBasicFields: BasicFieldsStub,
  ScheduleEventScopeSelector: true,
  ScheduleTargetPicker: true,
  ScheduleEventRecurrenceInput: true,
  ScheduleEventReminderInput: true,
  ScheduleEventScheduledAttachmentInput: true,
  ScheduleEventColorPicker: true,
  Checkbox: true,
  Textarea: true,
  Button: true,
}

interface ProbeValues {
  startDate: string
  endDate: string
  startTime: string
  endTime: string
}

async function mountForm(props: Record<string, unknown>): Promise<ProbeValues> {
  const wrapper = await mountSuspended(ScheduleEventForm, {
    props: { visible: false, scopeType: 'team', scopeId: 't1', isPersonal: false, ...props },
    global: { stubs: globalStubs },
  })
  // visible の watch で初期日時が適用されるため、false → true へ遷移させる。
  await wrapper.setProps({ visible: true })
  await flushPromises()
  await wrapper.vm.$nextTick()
  const probe = wrapper.get('[data-testid="datetime-probe"]')
  return {
    startDate: probe.attributes('data-start-date') ?? '',
    endDate: probe.attributes('data-end-date') ?? '',
    startTime: probe.attributes('data-start-time') ?? '',
    endTime: probe.attributes('data-end-time') ?? '',
  }
}

describe('ScheduleEventForm: 初期日時のプリセット（F03.19 §6.6.5）', () => {
  const originalTz = process.env.TZ

  beforeAll(() => {
    // 端末 TZ を UTC に固定する（ユーザー設定 Asia/Tokyo と食い違わせる）。
    process.env.TZ = 'UTC'
  })

  afterAll(() => {
    process.env.TZ = originalTz
  })

  beforeEach(() => {
    setActivePinia(createPinia())
    notificationMock.error.mockReset()
  })

  it('端末 TZ が UTC・ユーザー設定 TZ が Asia/Tokyo でも、initialStartAt の壁時計時刻がそのまま入る（AC-21h・R16）', async () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('UTC')

    const v = await mountForm({
      initialStartAt: '2026-08-06T09:00:00+09:00',
      initialEndAt: '2026-08-06T10:30:00+09:00',
    })

    expect(v.startTime).toBe('09:00')
    expect(v.endTime).toBe('10:30')
    expect(v.startDate).toBe('2026-08-06')
    expect(v.endDate).toBe('2026-08-06')
  }, 60000)

  it('夏時間の切替を跨ぐ日時でもユーザー TZ の壁時計が保たれる（America/New_York・DST 開始日）', async () => {
    authStoreStub.user.timezone = 'America/New_York'
    try {
      // 2026-03-08 は米国の DST 開始日（02:00 → 03:00）。その直後の 03:00 EDT (=07:00Z)。
      const v = await mountForm({
        initialStartAt: '2026-03-08T03:00:00-04:00',
        initialEndAt: '2026-03-08T04:00:00-04:00',
      })
      expect(v.startTime).toBe('03:00')
      expect(v.endTime).toBe('04:00')
      expect(v.startDate).toBe('2026-03-08')
    }
    finally {
      authStoreStub.user.timezone = 'Asia/Tokyo'
    }
  }, 60000)

  it('AC-22c: initialStartAt が無ければ従来どおり initialDate のみが効き、時刻はプリセットされない', async () => {
    const v = await mountForm({ initialDate: '2026-08-06' })

    expect(v.startDate).toBe('2026-08-06')
    expect(v.endDate).toBe('2026-08-06')
    // resetForm の既定値のまま（時刻は載らない）
    expect(v.startTime).toBe('09:00')
    expect(v.endTime).toBe('10:00')
  }, 60000)

  it('initialEndAt が無い場合、終了時刻は開始の1時間後に補完される（自動補正 watcher と同じ規則）', async () => {
    // 開始は 14:15 とする。フォーム初期値の終了（10:00）と +1時間後（15:15）が一致してしまうと
    // 「補完していない」状態でもテストが通ってしまうため、既定値と重ならない時刻を選ぶ。
    const v = await mountForm({ initialStartAt: '2026-08-06T14:15:00+09:00' })

    expect(v.startTime).toBe('14:15')
    // 抑止フラグで watcher を止めているぶん、呼び出し側が同じ規則を適用する責任を負う。
    expect(v.endTime).toBe('15:15')
    expect(v.startDate).toBe('2026-08-06')
    expect(v.endDate).toBe('2026-08-06')
  }, 60000)

  it('initialEndAt が無く 23:30 開始のとき、終了は翌日 00:30 へ繰り上がる（日付繰り上がりの境界）', async () => {
    const v = await mountForm({ initialStartAt: '2026-08-06T23:30:00+09:00' })

    expect(v.startTime).toBe('23:30')
    expect(v.startDate).toBe('2026-08-06')
    expect(v.endTime).toBe('00:30')
    expect(v.endDate).toBe('2026-08-07')
  }, 60000)

  it('initialStartAt は initialDate より優先される', async () => {
    const v = await mountForm({
      initialDate: '2026-01-01',
      initialStartAt: '2026-08-06T13:15:00+09:00',
      initialEndAt: '2026-08-06T14:00:00+09:00',
    })

    expect(v.startDate).toBe('2026-08-06')
    expect(v.startTime).toBe('13:15')
    expect(v.endTime).toBe('14:00')
  }, 60000)
})
