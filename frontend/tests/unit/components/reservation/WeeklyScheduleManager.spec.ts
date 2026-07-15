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

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getSlotTemplates: mockGetSlotTemplates,
    getLines: mockGetLines,
    createSlotTemplate: mockCreateSlotTemplate,
    updateSlotTemplate: mockUpdateSlotTemplate,
    deleteSlotTemplate: mockDeleteSlotTemplate,
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
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockNotifyWarn.mockReset()
  mockGetLines.mockResolvedValue({ data: [] })
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
