import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import SlotTemplateManager from '~/components/reservation/SlotTemplateManager.vue'

/**
 * SlotTemplateManager.vue（週間テンプレート管理・F03.4.2 §10）ユニットテスト — 番人
 *
 * 最重要観点（AC-2）: 曜日 value の3文字コード変換。
 *   写経元 ScheduleEventRecurrenceInput.vue の曜日トグルは 'MONDAY' フルネームを emit するが、
 *   BE の ReservationDayOfWeek enum は 'MON'..'SUN' の3文字大文字のみ受理する
 *   （フルネーム送信は Jackson デシリアライズ失敗で 400 — 設計書 §4/§10）。
 *   本テストは createSlotTemplate に渡る dayOfWeek が必ず 'MON' 形式であることを固定する。
 *
 * その他:
 *   AC-1: テンプレ0件で空状態（reservation.template.empty_state）を表示する
 *   AC-3: 曜日未選択では保存ボタンが disabled（フォームバリデーション最低限）
 *   AC-4: 「今すぐ枠を作成」で generate API が weeks 付きで呼ばれる
 *
 * 注: テスト環境の既定ロケールは en。Dialog は Teleport されるため document.body を走査する。
 */
const mockGetSlotTemplates = vi.fn()
const mockGetLines = vi.fn()
const mockCreateSlotTemplate = vi.fn()
const mockUpdateSlotTemplate = vi.fn()
const mockDeleteSlotTemplate = vi.fn()
const mockGenerateSlots = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getSlotTemplates: mockGetSlotTemplates,
    getLines: mockGetLines,
    createSlotTemplate: mockCreateSlotTemplate,
    updateSlotTemplate: mockUpdateSlotTemplate,
    deleteSlotTemplate: mockDeleteSlotTemplate,
    generateSlotsFromTemplates: mockGenerateSlots,
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

/** テンプレ1件（active）のレスポンス雛形。 */
const activeTemplate = {
  id: '0198aa-uuid',
  lineId: null,
  lineName: null,
  dayOfWeek: 'MON',
  startTime: '10:00:00',
  endTime: '13:00:00',
  capacity: 1,
  cellCount: 6,
  isActive: true,
}

beforeEach(() => {
  mockGetSlotTemplates.mockReset()
  mockGetLines.mockReset()
  mockCreateSlotTemplate.mockReset()
  mockGenerateSlots.mockReset()
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

describe('SlotTemplateManager.vue', () => {
  it('AC-1: テンプレ0件で空状態を表示する', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(SlotTemplateManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    expect(wrapper.html()).toContain(MSG_EMPTY_STATE)
  })

  it('AC-2【最重要】: 曜日トグルで選んだ月曜は 3文字コード MON で createSlotTemplate に渡る（MONDAY を送らない）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate.mockResolvedValue({ data: { id: 'tpl-1' } })

    const wrapper = await mountSuspended(SlotTemplateManager, {
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

    const wrapper = await mountSuspended(SlotTemplateManager, {
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

    const wrapper = await mountSuspended(SlotTemplateManager, {
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

  it('AC-4: 「今すぐ枠を作成」で generate API が weeks=4（既定）で呼ばれ、結果トーストが出る', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [activeTemplate], meta: { totalTemplates: 1, limit: 500 } },
    })
    mockGenerateSlots.mockResolvedValue({
      data: {
        generatedCount: 288,
        skippedExistingCount: 96,
        skippedClosedDayCount: 0,
        skippedOutsideHoursCount: 0,
        horizonFrom: '2026-07-06',
        horizonTo: '2026-08-02',
      },
    })

    const wrapper = await mountSuspended(SlotTemplateManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const generateBtn = wrapper.find('[data-testid="generate-now"]')
    expect(generateBtn.exists()).toBe(true)
    await generateBtn.trigger('click')
    await flush()

    expect(mockGenerateSlots).toHaveBeenCalledTimes(1)
    const [teamId, body] = mockGenerateSlots.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    expect(body.weeks).toBe(4)
    // 生成結果（288作成・96冪等スキップ）をトーストで報告する
    expect(mockNotifySuccess).toHaveBeenCalled()
  })

  it('AC-5（UX改善5点の1）: 曜日を複数選択して保存すると createSlotTemplate が選択数ぶん順に呼ばれ、各payloadのdayOfWeekが正しい3文字コードになる', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    mockCreateSlotTemplate.mockResolvedValue({ data: { id: 'tpl-1' } })

    const wrapper = await mountSuspended(SlotTemplateManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // ダイアログを開き、月・水・金の3曜日をトグルで複数選択する
    await wrapper.find('[data-testid="template-add"]').trigger('click')
    await flush()
    const monBtn = document.body.querySelector<HTMLButtonElement>('[data-day="MON"]')
    const wedBtn = document.body.querySelector<HTMLButtonElement>('[data-day="WED"]')
    const friBtn = document.body.querySelector<HTMLButtonElement>('[data-day="FRI"]')
    expect(monBtn).not.toBeNull()
    expect(wedBtn).not.toBeNull()
    expect(friBtn).not.toBeNull()
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
    for (const day of calledDays) {
      expect(VALID_DAY_CODES).toContain(day)
    }
    // 各 payload とも teamId・時刻・定員は共通
    for (const call of mockCreateSlotTemplate.mock.calls as Array<[string, Record<string, unknown>]>) {
      const [teamId, body] = call
      expect(teamId).toBe('team-slug')
      expect(body.startTime).toBe('09:00:00')
      expect(body.endTime).toBe('10:00:00')
      expect(body.capacity).toBe(1)
    }
  })

  it('AC-6: 曜日を再クリックすると選択が解除される（トグルOFF）', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })

    const wrapper = await mountSuspended(SlotTemplateManager, {
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

  it('AC-7（検分指摘）: 複数曜日の部分失敗（2件目で失敗）— 一覧を実状態へ同期し、成功曜日は選択から除去され失敗曜日のみ残る', async () => {
    mockGetSlotTemplates.mockResolvedValue({
      data: { templates: [], meta: { totalTemplates: 0, limit: 500 } },
    })
    // 1件目（MON）成功 → 2件目（WED）が RESERVATION_037（上限500行）で失敗する部分失敗シナリオ
    mockCreateSlotTemplate
      .mockResolvedValueOnce({ data: { id: 'tpl-1' } })
      .mockRejectedValueOnce({ data: { error: { code: 'RESERVATION_037' } } })

    const wrapper = await mountSuspended(SlotTemplateManager, {
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
    //     （ダイアログは開いたままなので、そのまま再試行しても MON を重複作成しない）
    expect(monBtn.className).not.toContain('bg-primary')
    expect(wedBtn.className).toContain('bg-primary')
    // ダイアログは閉じずに再試行可能（保存ボタンが残存し enabled）
    const saveBtn = findByTestId<HTMLButtonElement>('template-save')
    expect(saveBtn).not.toBeNull()
    expect(saveBtn!.disabled).toBe(false)
    // (c) 部分成功（1/2件作成済み）の warn トーストと、RESERVATION_037 のエラートーストの両方を伝達
    expect(mockNotifyWarn).toHaveBeenCalled()
    expect(mockNotifyError).toHaveBeenCalled()
    // 全滅ではないので成功トーストは出さない
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })
})
