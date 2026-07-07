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
})
