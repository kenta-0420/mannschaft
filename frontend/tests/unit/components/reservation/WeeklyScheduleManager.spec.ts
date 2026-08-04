import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import WeeklyScheduleManager from '~/components/reservation/WeeklyScheduleManager.vue'

/**
 * WeeklyScheduleManager.vue（旧 SlotTemplateManager・週間スケジュール管理・F03.4.5 §3.1/§3.2）
 * ユニットテスト — 番人
 *
 * 最重要観点（AC-FE17★）: 曜日 value の3文字コード変換。
 *   写経元 ScheduleEventRecurrenceInput.vue の曜日トグルは 'MONDAY' フルネームを emit するが、
 *   BE の ReservationDayOfWeek enum は 'MON'..'SUN' の3文字大文字のみ受理する
 *   （フルネーム送信は Jackson デシリアライズ失敗で 400 — 設計書 §4/§10）。
 *   本テストは createSlotTemplate に渡る dayOfWeek が必ず 'MON' 形式であることを固定する。
 *
 * F03.4.5 W2-1 改訂に伴う変更点:
 *   - 「今すぐ枠を作成」ボタン・weeks Select は撤去（AC-FE10）。
 *   - createSlotTemplate/updateSlotTemplate の応答は SlotTemplateSaveResponse
 *     （{ template, generation }）。保存成功時に generation を合算し1トーストで報告する（AC-FE7★）。
 *   - hasBusinessHours=false のとき空状態に導線を表示する（AC-FE9）。
 *
 * 注: テスト環境の既定ロケールは en。Dialog は Teleport されるため document.body を走査する。
 */
const mockGetSlotTemplates = vi.fn()
const mockGetLines = vi.fn()
const mockCreateSlotTemplate = vi.fn()
const mockUpdateSlotTemplate = vi.fn()
const mockDeleteSlotTemplate = vi.fn()
// F03.4.5 W2-2-FE §4 B) 定期予約不可枠（週次繰り返し）
const mockListRecurringBlockedTimes = vi.fn()
const mockCreateRecurringBlockedTime = vi.fn()
const mockUpdateRecurringBlockedTime = vi.fn()
const mockDeleteRecurringBlockedTime = vi.fn()
const mockGetRecurringBlockedTimeImpact = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getSlotTemplates: mockGetSlotTemplates,
    getLines: mockGetLines,
    createSlotTemplate: mockCreateSlotTemplate,
    updateSlotTemplate: mockUpdateSlotTemplate,
    deleteSlotTemplate: mockDeleteSlotTemplate,
    listRecurringBlockedTimes: mockListRecurringBlockedTimes,
    createRecurringBlockedTime: mockCreateRecurringBlockedTime,
    updateRecurringBlockedTime: mockUpdateRecurringBlockedTime,
    deleteRecurringBlockedTime: mockDeleteRecurringBlockedTime,
    getRecurringBlockedTimeImpact: mockGetRecurringBlockedTimeImpact,
  }),
}))

// 役割は ADMIN 固定（表示ガードの対象外にしてフォーム動作へ集中する）
mockNuxtImport('useRoleAccess', () => () => ({
  permissions: ref<string[]>([]),
  roleName: ref('ADMIN'),
  loading: ref(false),
  loadPermissions: async () => ({ ok: true }),
  can: () => true,
  isAdmin: computed(() => true),
  isAdminOrDeputy: computed(() => true),
  isMember: computed(() => true),
}))

// PrimeVue ToastService 依存を避けるためトーストをモック
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

// 強行登録の確認ダイアログ（F03.4.5 §6.2 W2-5-FE）を検証可能にするため useConfirm をモックする
// （ReservationList.spec.ts と同一パターン。実 PrimeVue ConfirmationService はグローバル
// <ConfirmDialog/> が無いと accept/reject を DOM から操作できないため）。
let confirmAcceptCallback: (() => void | Promise<void>) | null = null
mockNuxtImport('useConfirm', () => () => ({
  require: (opts: { accept: () => void | Promise<void> }) => { confirmAcceptCallback = opts.accept },
  close: vi.fn(),
}))

const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
  getFieldErrors: () => ({}),
}))

// i18n 実解決文字列（en ロケール）
const MSG_EMPTY_STATE = 'No weekly templates registered yet'
const MSG_NEED_BUSINESS_HOURS = 'Please set business hours first'

/** BE ReservationDayOfWeek が受理する正準コード（これ以外＝'MONDAY' 等は 400） */
const VALID_DAY_CODES = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

/** onMounted 内の並列 load（await 連鎖）を確実に流し切る。 */
async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

/** SlotTemplateSaveResponse 雛形（生成成功・非0件）。 */
function saveResponse(overrides: Partial<{
  generatedCount: number
  skippedExistingCount: number
  skippedClosedDayCount: number
  skippedOutsideHoursCount: number
  failed: boolean
}> = {}) {
  return {
    data: {
      template: { id: 'tpl-1' },
      generation: {
        generatedCount: 24,
        skippedExistingCount: 0,
        skippedClosedDayCount: 0,
        skippedOutsideHoursCount: 0,
        failed: false,
        ...overrides,
      },
    },
  }
}

beforeEach(() => {
  mockGetSlotTemplates.mockReset()
  mockGetLines.mockReset()
  mockCreateSlotTemplate.mockReset()
  mockUpdateSlotTemplate.mockReset()
  mockDeleteSlotTemplate.mockReset()
  mockListRecurringBlockedTimes.mockReset()
  mockCreateRecurringBlockedTime.mockReset()
  mockUpdateRecurringBlockedTime.mockReset()
  mockDeleteRecurringBlockedTime.mockReset()
  mockGetRecurringBlockedTimeImpact.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockNotifyWarn.mockReset()
  mockGetLines.mockResolvedValue({ data: [] })
  // 既存テスト（定期予約不可を扱わないシナリオ）が想定外の警告トースト等で汚染されないよう、
  // 既定は「0件」で安定させる（onMounted の loadRecurringRules が必ず呼ぶため）。
  mockListRecurringBlockedTimes.mockResolvedValue({ data: [] })
  mockGetRecurringBlockedTimeImpact.mockResolvedValue({ data: { affectedCount: 0, reservations: [] } })
  confirmAcceptCallback = null
})

afterEach(() => {
  // Teleport された DOM のクリーンアップ
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

describe('WeeklyScheduleManager.vue', () => {
  it('AC-1: テンプレ0件で空状態を表示する', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    expect(wrapper.html()).toContain(MSG_EMPTY_STATE)
  })

  it('AC-FE17★【最重要】: 曜日トグルで選んだ月曜は 3文字コード MON で createSlotTemplate に渡る（MONDAY を送らない）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate.mockResolvedValue(saveResponse())

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // ダイアログを開き、月曜トグルをクリック
    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')
    expect(monBtn).not.toBeNull()
    monBtn!.click()
    await flush()

    // 保存（開始/終了時刻は 30 分刻みの既定値がプリセットされている）
    const saveBtn = findByTestId<HTMLButtonElement>('template-save')
    expect(saveBtn!.disabled).toBe(false)
    saveBtn!.click()
    await flush()

    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(1)
    const [teamId, body] = mockCreateSlotTemplate.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    // 最重要: 3文字大文字コードで送ること（'MONDAY' は BE デシリアライズで 400）
    expect(body.dayOfWeek).toBe('MON')
    expect(body.dayOfWeek).not.toBe('MONDAY')
    expect(VALID_DAY_CODES).toContain(body.dayOfWeek)
    // 時刻は 30 分刻み（HH:mm:ss）
    expect(body.startTime).toBe('09:00:00')
    expect(body.endTime).toBe('10:00:00')
    expect(body.capacity).toBe(1)
  })

  it('AC-2b: 全曜日トグルの data-day が正準3文字コードのみで構成される（フルネーム混入の番人）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()

    const dayButtons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[data-day]'))
    expect(dayButtons).toHaveLength(7)
    for (const btn of dayButtons) {
      const day = btn.getAttribute('data-day')
      expect(VALID_DAY_CODES).toContain(day)
    }
  })

  it('AC-3: 曜日未選択では保存ボタンが disabled', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()

    const saveBtn = findByTestId<HTMLButtonElement>('template-save')
    expect(saveBtn).not.toBeNull()
    expect(saveBtn!.disabled).toBe(true)
    expect(mockCreateSlotTemplate).not.toHaveBeenCalled()
  })

  it('AC-FE10: 「今すぐ枠を作成」ボタン・週数 Select が存在しない', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: {
        templates: [{ id: 'tpl-1', dayOfWeek: 'MON', startTime: '10:00:00', endTime: '13:00:00', capacity: 1, isActive: true }],
        meta: { totalTemplates: 1, limit: 500 },
      },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    expect(wrapper.find('[data-testid="generate-now"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('generate_weeks')
  })

  it('AC-FE6/AC-FE7★: 曜日を複数選択して保存すると createSlotTemplate が選択数ぶん順に呼ばれ、generation が合算されて1回の成功トーストになる', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate
      .mockResolvedValueOnce(saveResponse({ generatedCount: 24 }))
      .mockResolvedValueOnce(saveResponse({ generatedCount: 24 }))
      .mockResolvedValueOnce(saveResponse({ generatedCount: 24 }))

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // ダイアログを開き、月・水・金の3曜日をトグルで複数選択する
    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')
    const wedBtn = document.body.querySelector<HTMLButtonElement>('[data-day="WED"]')
    const friBtn = document.body.querySelector<HTMLButtonElement>('[data-day="FRI"]')
    monBtn!.click()
    await flush()
    wedBtn!.click()
    await flush()
    friBtn!.click()
    await flush()

    const saveBtn = findByTestId<HTMLButtonElement>('template-save')
    expect(saveBtn!.disabled).toBe(false)
    saveBtn!.click()
    await flush()

    // 選択曜日ぶん（3件）createSlotTemplate が順に呼ばれる（曜日ごとのテンプレ行に展開）
    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(3)
    const calledDays = mockCreateSlotTemplate.mock.calls.map(
      call => (call as [string, Record<string, unknown>])[1].dayOfWeek,
    )
    expect(calledDays).toEqual(['MON', 'WED', 'FRI'])

    // 「今すぐ枠を作成」を押さず、3件ぶん合算（24*3=72件）の成功トーストが1回だけ出る
    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifySuccess.mock.calls[0] as [string, string]
    expect(message).toContain('72')
    expect(message).toContain('28')
  })

  it('AC-6: 曜日を再クリックすると選択が解除される（トグルOFF）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')!
    monBtn.click()
    await flush()
    expect(findByTestId<HTMLButtonElement>('template-save')!.disabled).toBe(false)

    // 再クリックで選択解除 → 選択0件に戻り保存不可
    monBtn.click()
    await flush()
    expect(findByTestId<HTMLButtonElement>('template-save')!.disabled).toBe(true)
  })

  it('AC-FE7★（検分指摘由来）: 複数曜日の部分失敗（2件目で呼び自体が失敗）— 一覧を実状態へ同期し、成功曜日は選択から除去され失敗曜日のみ残る', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    // 1件目（MON）成功 → 2件目（WED）が RESERVATION_037（上限500行）で失敗する部分失敗シナリオ
    mockCreateSlotTemplate
      .mockResolvedValueOnce(saveResponse())
      .mockRejectedValueOnce({ data: { error: { code: 'RESERVATION_037' } } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')!
    const wedBtn = document.body.querySelector<HTMLButtonElement>('[data-day="WED"]')!
    monBtn.click()
    await flush()
    wedBtn.click()
    await flush()

    const getTemplatesCallsBefore = mockGetSlotTemplates.mock.calls.length
    findByTestId<HTMLButtonElement>('template-save')!.click()
    await flush()

    // 選択順どおり2回呼ばれ、2回目で中断（3回目は呼ばれない）
    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(2)
    // (a) catch経路でも loadTemplates が再実行され、一覧が実状態（成功分のみ作成済み）へ同期される
    expect(mockGetSlotTemplates.mock.calls.length).toBeGreaterThan(getTemplatesCallsBefore)
    // (b) 成功済み MON は選択から除去・失敗した WED のみ選択が残る
    expect(monBtn.className).not.toContain('bg-primary')
    expect(wedBtn.className).toContain('bg-primary')
    // ダイアログは閉じずに再試行可能（保存ボタンが残存し enabled）
    const saveBtn = findByTestId<HTMLButtonElement>('template-save')
    expect(saveBtn).not.toBeNull()
    expect(saveBtn!.disabled).toBe(false)
    // (c) 部分失敗（1/2件成功）は「N曜日中M曜日で失敗」の警告トースト1本に集約する（§3.1 集約規則・AC-FE7★）
    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    const [, warnMessage] = mockNotifyWarn.mock.calls[0] as [string, string]
    expect(warnMessage).toContain('1')
    expect(warnMessage).toContain('2')
    // 全滅ではないので成功トースト・個別エラートーストは出さない（1本に集約）
    expect(mockNotifySuccess).not.toHaveBeenCalled()
    expect(mockNotifyError).not.toHaveBeenCalled()
  })

  it('AC-FE7★（generation.failed 由来）: HTTPは成功したが生成が失敗（generation.failed=true）した場合も部分失敗の警告トースト1本になる', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate.mockResolvedValue(saveResponse({ failed: true, generatedCount: 0 }))

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')!
    monBtn.click()
    await flush()
    findByTestId<HTMLButtonElement>('template-save')!.click()
    await flush()

    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(1)
    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })

  it('AC-FE8: generatedCount=0 かつ skippedOutsideHoursCount>0 で原因を明示する警告トーストが出る（S-11）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate.mockResolvedValue(
      saveResponse({ generatedCount: 0, skippedOutsideHoursCount: 4 }),
    )

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')!
    monBtn.click()
    await flush()
    findByTestId<HTMLButtonElement>('template-save')!.click()
    await flush()

    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifyWarn.mock.calls[0] as [string, string]
    expect(message.toLowerCase()).toContain('business hours')
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })

  it('AC-FE9: hasBusinessHours=false のとき空状態に導線が表示され、クリックで focus-business-hours が emit される', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug', hasBusinessHours: false },
    })
    await flush()

    expect(wrapper.html()).toContain(MSG_NEED_BUSINESS_HOURS)
    const focusBtn = wrapper.find('[data-testid="focus-business-hours"]')
    expect(focusBtn.exists()).toBe(true)
    await focusBtn.trigger('click')
    expect(wrapper.emitted('focus-business-hours')).toBeTruthy()
  })
})

/**
 * F03.4.5 W2-2-FE §4 B) 定期予約不可枠（週次繰り返し）— WeeklyScheduleManager 統合分のユニットテスト。
 *
 * 最重要観点（AC-R-FE1★）: 曜日 value の3文字コード変換。
 *   `openCreateRecurring(day)`（曜日行の「＋予約不可」クイック追加）で開いたダイアログの保存が、
 *   `createRecurringBlockedTime` に渡す dayOfWeek を必ず 'TUE' 形式で送ること（'TUESDAY' を送らない）。
 *
 * AC-R-9（§4.4/design R-9）: is_public トグルON時のみ reason_no_pii 注意ガイドが表示される（ON/OFF切替の番人）。
 * AC-R-FE4（§4.3）: 全日型を作らせない — 時刻 Select に show-clear が付かず、時刻レンジ無効時は保存不可。
 */
describe('WeeklyScheduleManager.vue — 定期予約不可枠（§4 B・W2-2-FE）', () => {
  it('AC-R-FE1★【最重要】: ヘッダーの「予約不可を追加」（曜日未指定＝既定MON）で保存すると、dayOfWeek は3文字コード MON で createRecurringBlockedTime に渡る（MONDAY を送らない）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateRecurringBlockedTime.mockResolvedValue({
      data: { id: 'rule-1', dayOfWeek: 'MON', startTime: '19:00:00', endTime: '20:00:00', reason: 'Training', isPublic: false, isActive: true },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()

    // 事由（必須）を入力（曜日・時刻は既定値のまま: dayOfWeek='MON'・19:00-20:00）
    const reasonInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-reason"]')
    expect(reasonInput).not.toBeNull()
    reasonInput!.value = 'Training'
    reasonInput!.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()
    await flush()

    const saveBtn = document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-save"]')
    expect(saveBtn).not.toBeNull()
    expect(saveBtn!.disabled, '曜日・時刻(既定19:00-20:00)・事由が揃っていれば保存可能').toBe(false)
    saveBtn!.click()
    await flush()

    expect(mockCreateRecurringBlockedTime).toHaveBeenCalledTimes(1)
    const [teamId, body] = mockCreateRecurringBlockedTime.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    // 最重要: 3文字大文字コードで送ること（'MONDAY' は BE デシリアライズで 400）
    expect(body.dayOfWeek).toBe('MON')
    expect(body.dayOfWeek).not.toBe('MONDAY')
    expect(VALID_DAY_CODES).toContain(body.dayOfWeek)
    expect(body.startTime).toBe('19:00:00')
    expect(body.endTime).toBe('20:00:00')
    expect(body.reason).toBe('Training')
    // 既定はチーム全体（lineId 未指定）
    expect(body.lineId).toBeUndefined()
  })

  it('AC-R-FE1b: 曜日行の「＋予約不可」クイック追加（day引数）で開くと、選択済み曜日が3文字コードのまま保存される', async () => {
    // 曜日グルーピング行のクイック追加ボタンから開いた場合の day 引数経路（Select 操作なしで固定される）を検証する。
    mockGetSlotTemplates.mockResolvedValue({
      data: {
        templates: [{ id: 'tpl-1', dayOfWeek: 'MON', startTime: '10:00:00', endTime: '13:00:00', capacity: 1, isActive: true }],
        meta: { totalTemplates: 1, limit: 500 },
      },
    })
    mockCreateRecurringBlockedTime.mockResolvedValue({
      data: { id: 'rule-2', dayOfWeek: 'FRI', startTime: '19:00:00', endTime: '20:00:00', reason: 'Cleaning', isPublic: false, isActive: true },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // 曜日グルーピング表示（1件テンプレがあるため表示される）から金曜行のクイック追加を押す
    await wrapper.find('[data-testid="day-recurring-add-FRI"]').trigger('click')
    await flush()

    const reasonInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-reason"]')
    expect(reasonInput).not.toBeNull()
    reasonInput!.value = 'Cleaning'
    reasonInput!.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()
    await flush()

    document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-save"]')!.click()
    await flush()

    expect(mockCreateRecurringBlockedTime).toHaveBeenCalledTimes(1)
    const [, body] = mockCreateRecurringBlockedTime.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.dayOfWeek).toBe('FRI')
    expect(body.dayOfWeek).not.toBe('FRIDAY')
  })

  it('AC-R-9: is_public トグルをONにすると reason_no_pii 注意ガイドが表示され、OFFに戻すと消える', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()

    // 既定 isPublic=false のため注意ガイドは非表示
    expect(document.body.querySelector('[data-testid="recurring-reason-no-pii"]')).toBeNull()

    // トグルON（native checkbox の click() はjsdomでも既定のtoggle動作＋change発火を行う。
    // 既存 monBtn!.click() 系パターンと同様、raw DOM操作を用いる — Dialog は Teleport 先が
    // document.body のため wrapper.find() では到達できない）
    const toggleInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-is-public-toggle"] input')
    expect(toggleInput).not.toBeNull()
    toggleInput!.click()
    await flush()

    expect(document.body.querySelector('[data-testid="recurring-reason-no-pii"]'), 'ON時は必ず表示される（AC R-9）').not.toBeNull()

    // トグルOFFへ戻す
    toggleInput!.click()
    await flush()

    expect(document.body.querySelector('[data-testid="recurring-reason-no-pii"]'), 'OFFに戻すと消える').toBeNull()
  })

  it('AC-R-FE4（§4.3）: 全日型を作らせない — 時刻レンジが無効（開始=終了）の間は保存不可、時刻 Select に show-clear が付かない', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()

    // 構造的な全日型拒否: 開始/終了 Select のどちらにも PrimeVue の clearicon（show-clear の目印）が無い。
    // 定期予約不可の時刻選択は空にできない作りになっている（§4.3 設計判断のUI裏取り）。
    const startSelect = document.body.querySelector('[data-testid="recurring-start-time"]')
    const endSelect = document.body.querySelector('[data-testid="recurring-end-time"]')
    expect(startSelect).not.toBeNull()
    expect(endSelect).not.toBeNull()
    expect(startSelect!.querySelector('[data-pc-section="clearicon"]')).toBeNull()
    expect(endSelect!.querySelector('[data-pc-section="clearicon"]')).toBeNull()

    // 事由未入力の間は（既定の時刻レンジ19:00-20:00は有効でも）保存不可
    const saveBtn = document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-save"]')
    expect(saveBtn!.disabled, '事由未入力の間は保存できない（BE @NotBlank と整合）').toBe(true)
    expect(mockCreateRecurringBlockedTime).not.toHaveBeenCalled()
  })

  it('AC-R-6: 409（RESERVATION_027・overlapする active 予約）応答は握りつぶさず、機能Bと同一のエラーメッセージで通知する', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateRecurringBlockedTime.mockRejectedValue({ data: { error: { code: 'RESERVATION_027' } } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()
    const reasonInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-reason"]')!
    reasonInput.value = 'Training'
    reasonInput.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()
    await flush()

    document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-save"]')!.click()
    await flush()

    expect(mockCreateRecurringBlockedTime).toHaveBeenCalledTimes(1)
    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    // ダイアログは閉じたままにならず、エラーで留まる（成功トーストは出ない）
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })

  it('AC-R-5: 上限超過（RESERVATION_052）は専用の上限メッセージで通知する', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateRecurringBlockedTime.mockRejectedValue({ data: { error: { code: 'RESERVATION_052' } } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()
    const reasonInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-reason"]')!
    reasonInput.value = 'Training'
    reasonInput.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()
    await flush()

    document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-save"]')!.click()
    await flush()

    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifyError.mock.calls[0] as [string, string]
    expect(message).toContain('50')
  })

  it('AC-R-list: 一覧取得済みの定期不可ルールは該当曜日行に赤系で描画され、事由・公開バッジが表示される', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockListRecurringBlockedTimes.mockResolvedValue({
      data: [{
        id: 'rule-9',
        teamId: 10,
        lineId: null,
        lineName: null,
        dayOfWeek: 'TUE',
        startTime: '19:00:00',
        endTime: '20:00:00',
        reason: 'Training',
        isPublic: true,
        isActive: true,
      }],
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    expect(wrapper.find('[data-testid="recurring-row-rule-9"]').exists()).toBe(true)
    expect(wrapper.html()).toContain('Training')
  })

  it('AC-R-toggle1: 一時停止/再開が成功すると updateRecurringBlockedTime に isActive のみが渡り、一覧が再読込される', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockListRecurringBlockedTimes.mockResolvedValue({
      data: [{
        id: 'rule-9', teamId: 10, lineId: null, lineName: null, dayOfWeek: 'TUE',
        startTime: '19:00:00', endTime: '20:00:00', reason: 'Training', isPublic: false, isActive: true,
      }],
    })
    mockUpdateRecurringBlockedTime.mockResolvedValue({
      data: { id: 'rule-9', dayOfWeek: 'TUE', startTime: '19:00:00', endTime: '20:00:00', reason: 'Training', isPublic: false, isActive: false },
    })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const listCallsBefore = mockListRecurringBlockedTimes.mock.calls.length
    await wrapper.find('[data-testid="recurring-toggle-rule-9"]').trigger('click')
    await flush()

    expect(mockUpdateRecurringBlockedTime).toHaveBeenCalledTimes(1)
    const [teamId, ruleId, body] = mockUpdateRecurringBlockedTime.mock.calls[0] as [string, string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    expect(ruleId).toBe('rule-9')
    expect(body).toEqual({ isActive: false })
    // 一覧が再読込される（一時停止後の表示反映）
    expect(mockListRecurringBlockedTimes.mock.calls.length).toBeGreaterThan(listCallsBefore)
  })

  it('AC-R-toggle2（検分指摘・軽2）: 一時停止/再開が409（RESERVATION_027）で失敗すると、保存経路と同じコード判定を通りつつ「一時停止/再開」文脈のメッセージで通知される（登録文脈の文言を誤用しない）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockListRecurringBlockedTimes.mockResolvedValue({
      data: [{
        id: 'rule-9', teamId: 10, lineId: null, lineName: null, dayOfWeek: 'TUE',
        startTime: '19:00:00', endTime: '20:00:00', reason: 'Training', isPublic: false, isActive: true,
      }],
    })
    mockUpdateRecurringBlockedTime.mockRejectedValue({ data: { error: { code: 'RESERVATION_027' } } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-toggle-rule-9"]').trigger('click')
    await flush()

    expect(mockUpdateRecurringBlockedTime).toHaveBeenCalledTimes(1)
    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifyError.mock.calls[0] as [string, string]
    // 一時停止/再開文脈の専用メッセージ（en: "Could not pause/resume ..."）で通知され、
    // 保存(作成/更新)文脈の "overlapping reservations" 系メッセージとは異なる文言になること
    // （handleApiError への直呼びだった旧実装は文言が経路ごとに不揃いだった）。
    expect(message.toLowerCase()).toContain('pause')
    // handleApiError（汎用フォールバック）は呼ばれない（コード判定で分岐している証跡）
    expect(mockHandleApiError).not.toHaveBeenCalled()
  })

  /**
   * 定期予約不可枠の強行登録（forceCancelConflicting・F03.4.5 §6.2「殿の裁定」W2-5-FE）。
   *
   * 観点:
   *   FORCE-1: impact で衝突あり（affectedCount>0）のとき「衝突する予約をキャンセルして登録する」
   *            ボタンが出て、確認ダイアログ（confirmDialog.require）を経由してから
   *            createRecurringBlockedTime に forceCancelConflicting:true が additive で渡る
   *            （既定 false・うっかり押せない形＝確認ダイアログ必須）
   *   FORCE-2: 実際にキャンセルされた件数（forceCancelledCount）を正直に通知する
   *   FORCE-3: 衝突なし（affectedCount=0）のときは強行登録ボタン自体が出ない
   */
  it('FORCE-1: 衝突ありのとき強行登録ボタンが出て、確認後に forceCancelConflicting:true で送信する', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockGetRecurringBlockedTimeImpact.mockResolvedValue({
      data: { affectedCount: 2, reservations: [{ reservationId: 1, userName: 'Reserver Y', startTime: '19:00:00', endTime: '20:00:00' }] },
    })
    mockCreateRecurringBlockedTime.mockResolvedValue({ data: { id: 'rule-force-1', forceCancelledCount: 2 } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()
    const reasonInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-reason"]')!
    reasonInput.value = 'Training'
    reasonInput.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()
    await flush()

    // 衝突ありのため通常の保存ボタンは disabled のまま（回帰なし）
    const saveBtn = document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-save"]')
    expect(saveBtn!.disabled).toBe(true)

    const forceBtn = document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-force-cancel-button"]')
    expect(forceBtn, '衝突ありのとき強行登録ボタンが出る').not.toBeNull()
    forceBtn!.click()
    await flush()

    // 確認ダイアログを経由するまでは実際の作成APIは呼ばれない（うっかり押せない形）
    expect(mockCreateRecurringBlockedTime).not.toHaveBeenCalled()
    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()
    await flush()

    expect(mockCreateRecurringBlockedTime).toHaveBeenCalledTimes(1)
    const [teamId, body] = mockCreateRecurringBlockedTime.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    expect(body.forceCancelConflicting).toBe(true)
  })

  it('FORCE-2: 強行登録で実際にキャンセルされた件数(forceCancelledCount)を通知する', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockGetRecurringBlockedTimeImpact.mockResolvedValue({
      data: { affectedCount: 3, reservations: [] },
    })
    mockCreateRecurringBlockedTime.mockResolvedValue({ data: { id: 'rule-force-2', forceCancelledCount: 3 } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()
    const reasonInput = document.body.querySelector<HTMLInputElement>('[data-testid="recurring-reason"]')!
    reasonInput.value = 'Training'
    reasonInput.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()
    await flush()

    document.body.querySelector<HTMLButtonElement>('[data-testid="recurring-force-cancel-button"]')!.click()
    await flush()
    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()
    await flush()

    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    const [, message] = mockNotifyWarn.mock.calls[0] as [string, string]
    expect(message).toContain('3')
    // 通常の成功トーストも別途出る（登録自体は成立している）
    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
  })

  it('FORCE-3: 衝突なし（affectedCount=0）のときは強行登録ボタンが出ない', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockGetRecurringBlockedTimeImpact.mockResolvedValue({ data: { affectedCount: 0, reservations: [] } })

    const wrapper = await mountSuspended(WeeklyScheduleManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="recurring-add"]').trigger('click')
    await flush()

    expect(document.body.querySelector('[data-testid="recurring-force-cancel-button"]')).toBeNull()
  })
})

/**
 * 週グリッドのドラッグ範囲選択（管理者の枠作成の手数削減）— 番人
 *
 * 座標→セル特定は `document.elementFromPoint` 方式のため、テストでは同関数をスタブして
 * 「今どのセルの上にいるか」を差し替える（happy-dom はレイアウトを持たないので実座標は使えない）。
 */

/** グリッドの表示開始は 06:00・30分刻み（`GRID_START_HOUR`）。'HH:mm' → 行番号。 */
function gridRow(hm: string): number {
  const [h, m] = hm.split(':').map(Number)
  return ((h ?? 0) - 6) * 2 + ((m ?? 0) === 30 ? 1 : 0)
}

/** グリッドは Teleport されない（ダイアログと違い wrapper 配下に描画される）ためマウント root を保持する。 */
let managerRoot: HTMLElement | null = null

async function mountManager(props: { teamId: string } = { teamId: 'team-slug' }) {
  const wrapper = await mountSuspended(WeeklyScheduleManager, { props })
  await flush()
  managerRoot = wrapper.element as HTMLElement
  return wrapper
}

function cellEl(day: string, hm: string): HTMLElement {
  const el = managerRoot?.querySelector<HTMLElement>(`[data-testid="slot-cell-${day}-${gridRow(hm)}"]`)
  if (!el) throw new Error(`セルが見つからない: ${day} ${hm}`)
  return el
}

function pointerEvent(type: string, init: PointerEventInit = {}): Event {
  if (typeof PointerEvent === 'function') {
    return new PointerEvent(type, { bubbles: true, button: 0, pointerType: 'mouse', ...init })
  }
  // happy-dom に PointerEvent が無い場合のフォールバック（pointerType はハンドラの分岐に必須）
  const ev = new MouseEvent(type, { bubbles: true, button: 0, ...init })
  Object.defineProperty(ev, 'pointerType', { value: init.pointerType ?? 'mouse' })
  return ev
}

/** マウスドラッグ（pointerdown → pointermove → pointerup）を再現する。 */
async function dragOverCells(from: { day: string; hm: string }, to: { day: string; hm: string }) {
  const startCell = cellEl(from.day, from.hm)
  const endCell = cellEl(to.day, to.hm)
  const spy = vi.spyOn(document, 'elementFromPoint').mockReturnValue(endCell)
  startCell.dispatchEvent(pointerEvent('pointerdown'))
  await flush()
  window.dispatchEvent(pointerEvent('pointermove', { clientX: 10, clientY: 10 }))
  await flush()
  window.dispatchEvent(pointerEvent('pointerup'))
  await flush()
  spy.mockRestore()
}

describe('WeeklyScheduleManager.vue — 週グリッドのドラッグ範囲選択', () => {
  beforeEach(() => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate.mockResolvedValue(saveResponse())
  })

  it('DRAG-1: グリッドが表示され、既存フォームからの作成導線も残っている（AC-1/AC-5）', async () => {
    const wrapper = await mountManager()

    expect(managerRoot!.querySelector('[data-testid="slot-drag-grid"]')).not.toBeNull()
    // フォーム導線（従来のダイアログ）は撤去しない
    expect(wrapper.find('[data-testid="template-add"]').exists()).toBe(true)
    // セルは <button>（キーボードフォーカス可能性を壊さない・AC-8）
    expect(cellEl('MON', '10:00').tagName).toBe('BUTTON')
  })

  it('DRAG-2★: ドラッグ範囲が正しい開始/終了時刻に変換され、定員だけの確認で作成される（AC-3）', async () => {
    await mountManager()

    await dragOverCells({ day: 'MON', hm: '10:00' }, { day: 'MON', hm: '11:30' })

    // 確定済みの曜日・時刻は再入力させない（表示のみ）
    const label = findByTestId('drag-range-label')
    expect(label).not.toBeNull()
    expect(label!.textContent).toContain('10:00 - 12:00')

    findByTestId<HTMLButtonElement>('drag-create-confirm')!.click()
    await flush()

    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(1)
    const [, body] = mockCreateSlotTemplate.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.dayOfWeek).toBe('MON')
    expect(body.startTime).toBe('10:00:00')
    expect(body.endTime).toBe('12:00:00')
    expect(body.capacity).toBe(1)
  })

  it('DRAG-3★: 複数曜日にまたがるドラッグで曜日ぶん createSlotTemplate が呼ばれる（AC-4）', async () => {
    await mountManager()

    await dragOverCells({ day: 'MON', hm: '10:00' }, { day: 'WED', hm: '11:30' })
    findByTestId<HTMLButtonElement>('drag-create-confirm')!.click()
    await flush()

    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(3)
    const days = mockCreateSlotTemplate.mock.calls.map(
      c => (c as [string, Record<string, unknown>])[1].dayOfWeek,
    )
    expect(days).toEqual(['MON', 'TUE', 'WED'])
    for (const d of days) expect(VALID_DAY_CODES).toContain(d)
  })

  it('DRAG-4★: 既存枠を含む範囲は弾かれ、API を呼ばない（AC-6）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: {
        templates: [{ id: 'tpl-1', dayOfWeek: 'MON', startTime: '10:00:00', endTime: '11:00:00', capacity: 1, isActive: true }],
        meta: { totalTemplates: 1, limit: 500 },
      },
    })

    await mountManager()

    // 既存枠のセルは視覚的に区別され、操作対象から外れている
    expect(cellEl('MON', '10:00').hasAttribute('disabled')).toBe(true)
    expect(cellEl('TUE', '10:00').hasAttribute('disabled')).toBe(false)

    // 空きセルから既存枠へ向かってなぞる（重複作成の API エラーを踏ませない）
    await dragOverCells({ day: 'MON', hm: '09:00' }, { day: 'MON', hm: '10:00' })

    expect(findByTestId('drag-create-confirm')).toBeNull()
    expect(mockCreateSlotTemplate).not.toHaveBeenCalled()
    expect(mockNotifyWarn).toHaveBeenCalled()
  })

  it('DRAG-5: ESC キーでドラッグ選択を取り消せる（AC-7）', async () => {
    await mountManager()

    // 始点だけ置いた状態（2ステップ選択の途中）で ESC
    cellEl('MON', '10:00').dispatchEvent(pointerEvent('pointerdown', { pointerType: 'touch' }))
    await flush()
    expect(cellEl('MON', '10:00').getAttribute('aria-pressed')).toBe('true')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()

    expect(cellEl('MON', '10:00').getAttribute('aria-pressed')).toBe('false')
    expect(findByTestId('drag-create-confirm')).toBeNull()
    expect(mockCreateSlotTemplate).not.toHaveBeenCalled()
  })

  it('DRAG-6: タッチはドラッグを乗っ取らず2タップで範囲を確定する（パン操作との衝突回避）', async () => {
    await mountManager()

    cellEl('MON', '10:00').dispatchEvent(pointerEvent('pointerdown', { pointerType: 'touch' }))
    await flush()
    // 1タップ目では確認ダイアログは出ない（＝この間ページのパンはブラウザに委ねられている）
    expect(findByTestId('drag-create-confirm')).toBeNull()

    cellEl('MON', '11:30').dispatchEvent(pointerEvent('pointerdown', { pointerType: 'touch' }))
    await flush()

    expect(findByTestId('drag-range-label')!.textContent).toContain('10:00 - 12:00')
  })

  it('DRAG-7: キーボード（Enter）だけでも範囲を確定して枠を作れる（AC-8）', async () => {
    await mountManager()

    cellEl('TUE', '14:00').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flush()
    cellEl('TUE', '15:30').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flush()

    findByTestId<HTMLButtonElement>('drag-create-confirm')!.click()
    await flush()

    expect(mockCreateSlotTemplate).toHaveBeenCalledTimes(1)
    const [, body] = mockCreateSlotTemplate.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.dayOfWeek).toBe('TUE')
    expect(body.startTime).toBe('14:00:00')
    expect(body.endTime).toBe('16:00:00')
  })
})
