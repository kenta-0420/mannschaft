import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import MenuManager from '~/components/reservation/MenuManager.vue'

/**
 * MenuManager.vue（予約メニュー管理・F03.4.1 §10）ユニットテスト — 番人
 *
 * 観点:
 *   AC-1: メニュー0件で空状態（reservation.menu.empty_state）＋ CTA「メニューを追加」を表示する
 *   AC-2: 名前未入力では保存ボタンが disabled（フォームバリデーション最低限）
 *   AC-3: 名前入力＋保存で createMenu が呼ばれ、既定（全ライン提供可）は lineIds: [] で送られる
 *
 * 注: テスト環境の既定ロケールは en。i18n 実解決の描画文字列（英語）で検証する。
 *     Dialog は Teleport で document.body にレンダリングされるため、document.body を直接走査する。
 */
const mockGetMenus = vi.fn()
const mockGetLines = vi.fn()
const mockCreateMenu = vi.fn()
const mockUpdateMenu = vi.fn()
const mockDeleteMenu = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getMenus: mockGetMenus,
    getLines: mockGetLines,
    createMenu: mockCreateMenu,
    updateMenu: mockUpdateMenu,
    deleteMenu: mockDeleteMenu,
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
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: vi.fn(),
  error: mockNotifyError,
  showSuccess: mockNotifySuccess,
  showError: mockNotifyError,
  showInfo: vi.fn(),
  showWarn: vi.fn(),
}))

const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
  getFieldErrors: () => ({}),
}))

// i18n 実解決文字列（en ロケール）
const MSG_EMPTY_STATE = 'No menus registered yet'
const CTA_ADD = 'Add menu'

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

/** onMounted 内の並列 load（await 連鎖）を確実に流し切る。 */
async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

beforeEach(() => {
  mockGetMenus.mockReset()
  mockGetLines.mockReset()
  mockCreateMenu.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockGetLines.mockResolvedValue({ data: [] })
})

afterEach(() => {
  // Teleport された DOM のクリーンアップ
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

describe('MenuManager.vue', () => {
  it('AC-1: メニュー0件で空状態とCTA「メニューを追加」を表示する', async () => {
    mockGetMenus.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(MenuManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const html = wrapper.html()
    expect(html).toContain(MSG_EMPTY_STATE)
    expect(html).toContain(CTA_ADD)
  })

  it('AC-2: 名前未入力では保存ボタンが disabled', async () => {
    mockGetMenus.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(MenuManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // ダイアログを開く（ヘッダーのボタンは wrapper 内・Dialog 中身のみ Teleport される）
    await wrapper.find('[data-testid="menu-add"]').trigger('click')
    await flush()

    const saveBtn = findByTestId<HTMLButtonElement>('menu-save')
    expect(saveBtn).not.toBeNull()
    expect(saveBtn!.disabled).toBe(true)
  })

  it('AC-3: 名前入力＋保存で createMenu が呼ばれ、既定は lineIds: []（全ライン提供可）で送られる', async () => {
    mockGetMenus.mockResolvedValue({ data: [] })
    mockCreateMenu.mockResolvedValue({ data: { id: 'menu-1' } })

    const wrapper = await mountSuspended(MenuManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    await wrapper.find('[data-testid="menu-add"]').trigger('click')
    await flush()

    // 名前を入力（PrimeVue InputText は input 要素に attrs を透過する）
    const nameInput = findByTestId<HTMLInputElement>('menu-name')
    expect(nameInput).not.toBeNull()
    nameInput!.value = 'カット'
    nameInput!.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()

    const saveBtn = findByTestId<HTMLButtonElement>('menu-save')
    expect(saveBtn!.disabled).toBe(false)
    saveBtn!.click()
    await flush()

    expect(mockCreateMenu).toHaveBeenCalledTimes(1)
    const [teamId, body] = mockCreateMenu.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    expect(body.name).toBe('カット')
    // 既定の所要時間は 30 分（30 の倍数・30〜480 の範囲内）
    expect(body.durationMinutes).toBe(30)
    // 既定は「全ての予約対象で提供」= 空配列（設計書 §4: 空配列 = 全ライン提供可）
    expect(body.lineIds).toEqual([])
  })
})
