import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationBusinessHoursManager from '~/components/reservation/ReservationBusinessHoursManager.vue'

/**
 * ReservationBusinessHoursManager.vue（営業時間管理・F03.4.5 §3.2）ユニットテスト — 番人
 *
 * 最重要観点（AC-FE5★）: updateBusinessHours の送信形が BE DTO
 * （`{ hours: [...] }`・`dayOfWeek` 3文字大文字・`isOpen`）と一致すること。
 *
 * その他:
 *   AC-FE1: 7曜日ぶんの行（トグル＋時刻）が描画される
 *   AC-FE2: 保存で PUT が呼ばれ、応答の generation.generatedCount が成功トーストに反映される
 *   AC-FE3: 縮小方向（isOpen true→false）の保存は confirm 確認（shrink_note）を経由する
 *   AC-FE4: generation.failed=true は保存成立の上で警告トースト（黙殺しない）
 *
 * 注: テスト環境の既定ロケールは en。
 */
const mockGetBusinessHours = vi.fn()
const mockUpdateBusinessHours = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getBusinessHours: mockGetBusinessHours,
    updateBusinessHours: mockUpdateBusinessHours,
  }),
}))

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

/** AC-FE18: ConfirmDialog は app.vue 一本化済みのため useConfirm().require() を直呼びする前提でモックする。 */
let confirmAcceptCallback: (() => void | Promise<void>) | null = null
const mockConfirmRequire = vi.fn((opts: { accept: () => void | Promise<void> }) => {
  confirmAcceptCallback = opts.accept
})
mockNuxtImport('useConfirm', () => () => ({
  require: mockConfirmRequire,
  close: vi.fn(),
}))

const VALID_DAY_CODES = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

/**
 * 本コンポーネントは Dialog を持たず（PrimeVue Teleport 対象外）通常のツリー内に描画されるため、
 * document.body ではなく wrapper 経由で探索する（Teleport 前提の findByTestId は使えない）。
 */
function findInWrapper<T extends Element = HTMLElement>(
  wrapper: { find: (selector: string) => { exists: () => boolean; element: Element } },
  testId: string,
): T | null {
  const found = wrapper.find(`[data-testid="${testId}"]`)
  return found.exists() ? (found.element as T) : null
}

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

function businessHourEntry(dayOfWeek: string, isOpen: boolean, openTime?: string, closeTime?: string, endsNextDay = false) {
  return {
    id: 1,
    teamId: 10,
    businessStatus: { dayOfWeek, isOpen, openTime, closeTime, endsNextDay },
  }
}

beforeEach(() => {
  mockGetBusinessHours.mockReset()
  mockUpdateBusinessHours.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockNotifyWarn.mockReset()
  mockHandleApiError.mockReset()
  mockConfirmRequire.mockClear()
  confirmAcceptCallback = null
})

describe('ReservationBusinessHoursManager.vue', () => {
  it('AC-FE1: 全曜日が休業（既定値）で7行描画される', async () => {
    mockGetBusinessHours.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    for (const day of VALID_DAY_CODES) {
      expect(wrapper.find(`[data-testid="business-hours-row-${day}"]`).exists()).toBe(true)
    }
  })

  it('AC-FE5★【最重要】: 変更なしで保存すると updateBusinessHours に {hours:[7件・3文字大文字dayOfWeek・isOpen]} が渡る', async () => {
    mockGetBusinessHours.mockResolvedValue({ data: [] })
    mockUpdateBusinessHours.mockResolvedValue({
      data: { hours: [], generation: { generatedCount: 0, skippedExistingCount: 0, skippedClosedDayCount: 0, skippedOutsideHoursCount: 0, failed: false } },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    findInWrapper<HTMLButtonElement>(wrapper, 'business-hours-save')!.click()
    await flush()

    expect(mockUpdateBusinessHours).toHaveBeenCalledTimes(1)
    const [teamId, hours] = mockUpdateBusinessHours.mock.calls[0] as [string, Array<Record<string, unknown>>]
    expect(teamId).toBe('team-slug')
    expect(hours).toHaveLength(7)
    for (const entry of hours) {
      // 最重要: 3文字大文字コードで送ること（'MONDAY' 等のフルネームは BE デシリアライズで 400）
      expect(VALID_DAY_CODES).toContain(entry.dayOfWeek)
      expect(typeof entry.isOpen).toBe('boolean')
      // 既定値は全曜日休業のため isOpen=false・時刻は送らない
      expect(entry.isOpen).toBe(false)
      expect(entry.openTime).toBeUndefined()
      expect(entry.closeTime).toBeUndefined()
      expect(entry.endsNextDay).toBe(false)
    }
  })

  it('AC-FE5★（開店時刻フォーマット）: GET で月曜が営業中の場合、変更なし保存でも isOpen=true・時刻はHH:mm:00形式で送られる', async () => {
    mockGetBusinessHours.mockResolvedValue({
      data: [businessHourEntry('MON', true, '09:00:00', '18:00:00')],
    })
    mockUpdateBusinessHours.mockResolvedValue({
      data: { hours: [], generation: { generatedCount: 0, skippedExistingCount: 0, skippedClosedDayCount: 0, skippedOutsideHoursCount: 0, failed: false } },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    findInWrapper<HTMLButtonElement>(wrapper, 'business-hours-save')!.click()
    await flush()

    // 縮小/変更が無いため confirm を経由せず直接保存される
    expect(mockUpdateBusinessHours).toHaveBeenCalledTimes(1)
    const [, hours] = mockUpdateBusinessHours.mock.calls[0] as [string, Array<Record<string, unknown>>]
    const mon = hours.find(h => h.dayOfWeek === 'MON')!
    expect(mon.isOpen).toBe(true)
    expect(mon.openTime).toBe('09:00:00')
    expect(mon.closeTime).toBe('18:00:00')
    expect(mon.endsNextDay).toBe(false)
  })

  it('AC-16: 終了翌日の営業時間は endsNextDay=true を保持して送信する', async () => {
    mockGetBusinessHours.mockResolvedValue({
      data: [businessHourEntry('MON', true, '22:00:00', '04:00:00', true)],
    })
    mockUpdateBusinessHours.mockResolvedValue({
      data: { hours: [], generation: { generatedCount: 0, failed: false } },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()
    await wrapper.find('[data-testid="business-hours-save"]').trigger('click')
    await flush()

    const [, hours] = mockUpdateBusinessHours.mock.calls[0] as [string, Array<Record<string, unknown>>]
    const mon = hours.find(h => h.dayOfWeek === 'MON')!
    expect(mon.endsNextDay).toBe(true)
    expect(mon.openTime).toBe('22:00:00')
    expect(mon.closeTime).toBe('04:00:00')
  })

  it('AC-FE2: 保存成功で generation.generatedCount を含む成功トーストが出る', async () => {
    mockGetBusinessHours.mockResolvedValue({ data: [] })
    mockUpdateBusinessHours.mockResolvedValue({
      data: {
        hours: [],
        generation: { generatedCount: 12, skippedExistingCount: 0, skippedClosedDayCount: 0, skippedOutsideHoursCount: 0, failed: false },
      },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()
    findInWrapper<HTMLButtonElement>(wrapper, 'business-hours-save')!.click()
    await flush()

    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifySuccess.mock.calls[0] as [string, string]
    expect(message).toContain('12')
    expect(mockNotifyWarn).not.toHaveBeenCalled()
  })

  it('AC-FE8: generatedCount=0 かつ skippedOutsideHoursCount>0 で generated_zero_hint（原因明示）を warn する（S-11・成功トーストにしない）', async () => {
    // 営業時間 PUT 自体は成立（failed=false）だが、営業時間外/定休日により枠が1件も生成されなかったケース。
    // 「保存したのに0件」の無言の混乱を防ぐため、成功トーストではなく原因を明示する警告トーストを出す
    // （ReservationBusinessHoursManager.vue:140-143 の分岐。WeeklyScheduleManager 側 AC-FE8 と対称の番人）。
    mockGetBusinessHours.mockResolvedValue({ data: [] })
    mockUpdateBusinessHours.mockResolvedValue({
      data: {
        hours: [],
        generation: { generatedCount: 0, skippedExistingCount: 0, skippedClosedDayCount: 0, skippedOutsideHoursCount: 4, failed: false },
      },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()
    findInWrapper<HTMLButtonElement>(wrapper, 'business-hours-save')!.click()
    await flush()

    expect(mockUpdateBusinessHours).toHaveBeenCalledTimes(1)
    // 成功トーストではなく警告トーストで原因（営業時間外/定休日）を明示する
    expect(mockNotifySuccess).not.toHaveBeenCalled()
    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifyWarn.mock.calls[0] as [string, string]
    // en ロケールの generated_zero_hint 本文（原因明示）で呼ばれること
    expect(message.toLowerCase()).toContain('business hours')
  })

  it('AC-FE4: generation.failed=true は保存成立の上で警告トースト（黙殺しない）', async () => {
    mockGetBusinessHours.mockResolvedValue({ data: [] })
    mockUpdateBusinessHours.mockResolvedValue({
      data: {
        hours: [],
        generation: { generatedCount: 0, skippedExistingCount: 0, skippedClosedDayCount: 0, skippedOutsideHoursCount: 0, failed: true },
      },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()
    findInWrapper<HTMLButtonElement>(wrapper, 'business-hours-save')!.click()
    await flush()

    expect(mockUpdateBusinessHours).toHaveBeenCalledTimes(1)
    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })

  it('AC-FE3: 営業中の曜日を休業へトグルして保存すると、縮小確認（confirm.require・shrink_note）を経由する', async () => {
    mockGetBusinessHours.mockResolvedValue({
      data: [businessHourEntry('MON', true, '09:00:00', '18:00:00')],
    })
    mockUpdateBusinessHours.mockResolvedValue({
      data: { hours: [], generation: { generatedCount: 0, skippedExistingCount: 0, skippedClosedDayCount: 0, skippedOutsideHoursCount: 0, failed: false } },
    })

    const wrapper = await mountSuspended(ReservationBusinessHoursManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // 月曜のトグルを OFF にする（営業中→休業＝縮小方向）。
    // PrimeVue ToggleSwitch の v-model 更新は Vue のイベントシステム経由のため、
    // 素の DOM click() ではなく Vue Test Utils の trigger('click') を使う（jsdom + PrimeVue の実挙動確認済み）。
    const input = wrapper.find('[data-testid="business-hours-toggle-MON"] input')
    expect(input.exists()).toBe(true)
    await input.trigger('click')
    await input.trigger('change')
    await flush()

    await wrapper.find('[data-testid="business-hours-save"]').trigger('click')
    await flush()

    // 縮小方向のため confirm.require が呼ばれ、accept するまで updateBusinessHours は呼ばれない
    expect(mockConfirmRequire).toHaveBeenCalledTimes(1)
    expect(mockUpdateBusinessHours).not.toHaveBeenCalled()

    // 確認ダイアログで「保存する」を押すと実際に保存が実行される
    expect(confirmAcceptCallback).not.toBeNull()
    await confirmAcceptCallback!()
    await flush()

    expect(mockUpdateBusinessHours).toHaveBeenCalledTimes(1)
    const [, hours] = mockUpdateBusinessHours.mock.calls[0] as [string, Array<Record<string, unknown>>]
    const mon = hours.find(h => h.dayOfWeek === 'MON')!
    expect(mon.isOpen).toBe(false)
  })
})
