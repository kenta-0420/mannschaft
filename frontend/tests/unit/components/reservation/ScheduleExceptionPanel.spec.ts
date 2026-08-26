import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import dayjs from 'dayjs'
import ScheduleExceptionPanel from '~/components/reservation/ScheduleExceptionPanel.vue'

/**
 * ScheduleExceptionPanel.vue（例外日カレンダー・F03.4.5 §3.3）ユニットテスト — 番人
 *
 * W2-1 第二隊の受け入れ条件を固定する:
 *   AC-FE13: 日クリックで「休業にする/臨時営業する」2択ダイアログが出る
 *   AC-FE14: 休業ダイアログで impact API を全日条件で呼び、予約ありなら警告カード＋登録disabled
 *   AC-FE15: 臨時営業ダイアログの曜日ダイヤ既定=当日曜日・実行成功で special_done＋単日ビュー導線
 *   AC-FE16: 同日全日休業ありのとき臨時営業は blocked_conflict 警告＋実行ボタンdisabled
 *
 * 注: テスト環境の既定ロケールは en。Dialog は Teleport されるため document.body を走査する
 *     （写経元 WeeklyScheduleManager.spec.ts / ReservationBusinessHoursManager.spec.ts）。
 */
const mockGetBlockedTimeImpact = vi.fn()
const mockCreateBlockedTime = vi.fn()
const mockListBlockedTimes = vi.fn()
const mockGenerateSingleDaySlots = vi.fn()
const mockDeleteBlockedTime = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getBlockedTimeImpact: mockGetBlockedTimeImpact,
    createBlockedTime: mockCreateBlockedTime,
    listBlockedTimes: mockListBlockedTimes,
    generateSingleDaySlots: mockGenerateSingleDaySlots,
    deleteBlockedTime: mockDeleteBlockedTime,
  }),
}))

mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
const mockNotifyWarn = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: mockNotifyWarn,
  error: mockNotifyError,
  showSuccess: mockNotifySuccess,
  showError: mockNotifyError,
  showInfo: vi.fn(),
  showWarn: mockNotifyWarn,
}))

const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
  getFieldErrors: () => ({}),
}))

/** Dialog は Teleport されるため document.body から探索する。 */
function findInBody<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

/**
 * 生きた時計は使わない（根治）。
 *
 * 元は `dayjs().add(2, 'day').toDate()` で「今日」を実時計から求めていたが、
 * このコンポーネントは日付文字列化に明示 `.tz('Asia/Tokyo')` を使う一方、
 * このテストは既定（実行プロセスのローカルTZ）で `dayjs().format()` していたため、
 * CI（TZ=UTC）で UTC 15:00〜24:00（=JSTでは既に翌日）の時間帯に実行されると
 * 期待値とコンポーネント実測値の日付が1日ずれて落ちる時限爆弾だった
 * （実測: TZ=UTC・システム時刻を UTC 2026-08-11T15:15:00Z に固定して再現済み）。
 *
 * 相対日付への変更は解にならない（「深夜に走ると壊れる」別の時限爆弾に化けるだけ）。
 * 正解は時計を止めること。`vi.setSystemTime` で日付境界を跨がない安全な瞬間
 * （UTC 03:00 = JST 12:00、UTC/JSTどちらのTZで解釈しても同じ暦日になる昼間）に固定し、
 * スイート全体を通してその時刻のまま実行する（afterAll で必ず実時計に戻す）。
 */
vi.useFakeTimers({ toFake: ['Date'] })
vi.setSystemTime(new Date('2026-08-11T03:00:00Z'))

afterAll(() => {
  vi.useRealTimers()
})

/** 2日先（明日以降〜90日以内の中央値）を対象日に使う。境界値の揺れを避ける。 */
const targetDate = dayjs().add(2, 'day').toDate()
const targetDateIso = dayjs(targetDate).format('YYYY-MM-DD')

beforeEach(() => {
  mockGetBlockedTimeImpact.mockReset()
  mockCreateBlockedTime.mockReset()
  mockListBlockedTimes.mockReset()
  mockGenerateSingleDaySlots.mockReset()
  mockDeleteBlockedTime.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockNotifyWarn.mockReset()
  mockHandleApiError.mockReset()
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())

  mockGetBlockedTimeImpact.mockResolvedValue({ data: { affectedCount: 0, reservations: [] } })
  mockCreateBlockedTime.mockResolvedValue({ data: {} })
  mockListBlockedTimes.mockResolvedValue({ data: [] })
  mockGenerateSingleDaySlots.mockResolvedValue({ data: { generatedCount: 4, skippedExistingCount: 0 } })
  mockDeleteBlockedTime.mockResolvedValue({})
})

async function clickDate(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  const datePicker = wrapper.findComponent({ name: 'DatePicker' })
  expect(datePicker.exists()).toBe(true)
  await datePicker.vm.$emit('date-select', targetDate)
  await flushPromises()
}

describe('ScheduleExceptionPanel.vue（例外日カレンダー・F03.4.5 §3.3）', () => {
  it('AC-FE13: 日クリックで「休業にする/臨時営業する」2択ダイアログが出る', async () => {
    const wrapper = await mountSuspended(ScheduleExceptionPanel, {
      props: { teamId: 'team-slug' },
    })
    await clickDate(wrapper)

    expect(findInBody('exception-choice-close'), '「この日を休業にする」選択肢が出ること').toBeTruthy()
    expect(findInBody('exception-choice-special'), '「臨時営業する」選択肢が出ること').toBeTruthy()
  })

  it('AC-FE14: 休業ダイアログは impact API を全日条件（date+resourceType=TEAM）で呼び、予約ありなら警告カード＋登録ボタンdisabled', async () => {
    mockGetBlockedTimeImpact.mockResolvedValue({
      data: {
        affectedCount: 2,
        reservations: [
          { reservationId: 1, userName: 'Alice', startTime: '10:00:00', endTime: '10:30:00', status: 'CONFIRMED' },
          { reservationId: 2, userName: 'Bob', startTime: '14:00:00', endTime: '14:30:00', status: 'PENDING' },
        ],
      },
    })

    const wrapper = await mountSuspended(ScheduleExceptionPanel, {
      props: { teamId: 'team-slug' },
    })
    await clickDate(wrapper)
    findInBody<HTMLButtonElement>('exception-choice-close')!.click()
    await flushPromises()

    expect(mockGetBlockedTimeImpact).toHaveBeenCalledWith('team-slug', {
      date: targetDateIso,
      resourceType: 'TEAM',
    })

    const submitBtn = findInBody<HTMLButtonElement>('exception-close-submit')
    expect(submitBtn, '登録ボタンが描画されること').toBeTruthy()
    expect(submitBtn!.disabled, '有効な予約が残っている間は登録ボタンがdisabledであること').toBe(true)
    expect(document.body.textContent).toContain('Alice')
    expect(document.body.textContent).toContain('Bob')
  })

  it('AC-FE14b: impact が0件なら登録ボタンは有効で、登録すると createBlockedTime が全日・TEAM軸で呼ばれる', async () => {
    const wrapper = await mountSuspended(ScheduleExceptionPanel, {
      props: { teamId: 'team-slug' },
    })
    await clickDate(wrapper)
    findInBody<HTMLButtonElement>('exception-choice-close')!.click()
    await flushPromises()

    const submitBtn = findInBody<HTMLButtonElement>('exception-close-submit')
    expect(submitBtn!.disabled, '予約が無ければ登録ボタンは有効であること').toBe(false)

    submitBtn!.click()
    await flushPromises()

    expect(mockCreateBlockedTime).toHaveBeenCalledWith('team-slug', expect.objectContaining({
      blockedDate: targetDateIso,
      resourceType: 'TEAM',
    }))
    expect(mockNotifySuccess).toHaveBeenCalled()
  })

  it('AC-FE15: 臨時営業ダイアログは曜日ダイヤの既定が当日曜日で、実行成功で special_done＋単日ビュー導線が出る', async () => {
    const wrapper = await mountSuspended(ScheduleExceptionPanel, {
      props: { teamId: 'team-slug' },
    })
    await clickDate(wrapper)
    findInBody<HTMLButtonElement>('exception-choice-special')!.click()
    await flushPromises()

    // 衝突確認（listBlockedTimes）が対象日で呼ばれること
    expect(mockListBlockedTimes).toHaveBeenCalledWith('team-slug', { from: targetDateIso, to: targetDateIso })

    const daySelect = wrapper.findComponent({ name: 'Select' })
    expect(daySelect.exists()).toBe(true)
    const expectedDow = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'][targetDate.getDay()]
    expect(daySelect.props('modelValue'), '既定の曜日ダイヤが選択日の実曜日と一致すること').toBe(expectedDow)

    const submitBtn = findInBody<HTMLButtonElement>('exception-special-submit')
    expect(submitBtn!.disabled, '衝突なし・日付有効なら実行ボタンは有効であること').toBe(false)
    submitBtn!.click()
    await flushPromises()

    expect(mockGenerateSingleDaySlots).toHaveBeenCalledWith('team-slug', {
      date: targetDateIso,
      sourceDayOfWeek: expectedDow,
    })
    expect(mockNotifySuccess).toHaveBeenCalledWith(expect.stringContaining(targetDateIso))
    expect(findInBody('exception-special-goto-book'), '単日ビュー確認への導線ボタンが出ること').toBeTruthy()
  })

  it('AC-FE16: 同日に全日休業（TEAM・全日）があるとき、臨時営業は blocked_conflict 警告＋実行ボタンdisabled', async () => {
    mockListBlockedTimes.mockResolvedValue({
      data: [
        {
          id: 99,
          resource: { resourceType: 'TEAM' },
          timeSlot: { blockedDate: targetDateIso, startTime: null, endTime: null },
        },
      ],
    })

    const wrapper = await mountSuspended(ScheduleExceptionPanel, {
      props: { teamId: 'team-slug' },
    })
    await clickDate(wrapper)
    findInBody<HTMLButtonElement>('exception-choice-special')!.click()
    await flushPromises()

    // en ロケール既定値（reservation.exception_day.blocked_conflict）
    expect(document.body.textContent).toContain('This day is set to closed')
    const submitBtn = findInBody<HTMLButtonElement>('exception-special-submit')
    expect(submitBtn!.disabled, '全日休業と衝突する場合は実行ボタンがdisabledであること').toBe(true)

    // その場で衝突を解除できる導線（delete）が存在すること
    const deleteBtn = findInBody<HTMLButtonElement>('exception-conflict-delete')
    expect(deleteBtn, '衝突している休業を削除するボタンが出ること').toBeTruthy()
  })

  it('AC-FE16b: 時間帯指定の休業（全日ではない）は衝突とみなさず、実行ボタンは有効なまま', async () => {
    mockListBlockedTimes.mockResolvedValue({
      data: [
        {
          id: 100,
          resource: { resourceType: 'TEAM' },
          timeSlot: { blockedDate: targetDateIso, startTime: '10:00:00', endTime: '12:00:00' },
        },
      ],
    })

    const wrapper = await mountSuspended(ScheduleExceptionPanel, {
      props: { teamId: 'team-slug' },
    })
    await clickDate(wrapper)
    findInBody<HTMLButtonElement>('exception-choice-special')!.click()
    await flushPromises()

    const submitBtn = findInBody<HTMLButtonElement>('exception-special-submit')
    expect(submitBtn!.disabled, '部分時間帯の休業は全日休業ではないため実行ボタンは有効のまま').toBe(false)
  })
})
