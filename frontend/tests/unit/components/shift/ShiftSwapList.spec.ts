import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ShiftSwapList from '~/components/shift/ShiftSwapList.vue'

/**
 * ShiftSwapList.vue ユニットテスト — CMP-260908-2116「シフト交代の承諾・却下がUIから永久に押せない」の番人。
 *
 * 観点（受け入れ条件 8〜10 に対応）:
 *   AC-8 : 承諾ボタンが「承諾できる行」に実際に表示される。
 *          旧実装は `accepterId === 自分` を条件にしていたが、`accepterId` は承諾した瞬間に
 *          初めて確定するため承諾前は常に null であり、条件は誰に対しても成立しなかった
 *          （＝承諾ボタンが永久に出なかった）。その回帰防止。
 *   AC-9 : 承認・却下ボタンは「管理者かつ status=ACCEPTED」の行にだけ出る。
 *          旧実装は PENDING 行に却下を出していたが BE は ACCEPTED しか受け付けない矛盾があった。
 *          さらに送信値は BE と同じ**大文字** APPROVE / REJECT であること（旧実装は小文字 'reject'
 *          を送っており、仮に押せても必ず失敗した）。
 *   AC-10: 申請者本人には「却下」ではなく「取り下げ」が出る。
 *
 * 注: テスト環境の既定ロケールは en のため、判定は文言ではなく data-testid で行う。
 */
const mockListSwapRequests = vi.fn()
const mockAcceptSwap = vi.fn()
const mockResolveSwap = vi.fn()
const mockDeleteSwapRequest = vi.fn()

mockNuxtImport('useShiftApi', () => () => ({
  listSwapRequests: mockListSwapRequests,
  acceptSwap: mockAcceptSwap,
  resolveSwap: mockResolveSwap,
  deleteSwapRequest: mockDeleteSwapRequest,
}))

const CURRENT_USER_ID = 20

// useAuthStore は「素の差し替え」にしてはならない。
// app/plugins/auth.client.ts が Nuxt アプリ初期化のたびに `loadFromStorage()` を呼び、
// `isAuthenticated` が真なら `armProactiveRefresh()` がトークン更新を走らせて
// 失敗時に `logout()` を呼ぶ。これらが欠けたスタブを渡すとプラグインが初期化中に落ち、
// その後始末で router のナビゲーションが走って jsdom 破棄後に
// `ReferenceError: history is not defined` の Unhandled Rejection になる
// （テストは全件 green のまま vitest だけが exit 1 になる。#2609 と同種）。
// 既存の金型（tests/unit/components/dashboard/ActionRequiredModalsInitialLoad.spec.ts ほか）に
// 揃え、プラグインが触る API を欠かさずに持つスタブをモジュール単位で差し替える。
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    currentUser: { id: CURRENT_USER_ID },
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
    logout: vi.fn(),
  }),
}))

mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}))

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

function swap(overrides: Record<string, unknown>) {
  return {
    id: 1,
    slotId: 200,
    requesterId: 10,
    accepterId: null,
    status: 'PENDING',
    reason: '体調不良のため',
    adminNote: null,
    resolvedBy: null,
    resolvedAt: null,
    createdAt: '2026-09-08T10:00:00',
    recipientMode: 'SPECIFIC',
    targetUserIds: [CURRENT_USER_ID],
    claimedBy: null,
    claimedAt: null,
    ...overrides,
  }
}

async function mount(swaps: unknown[], canManage = false) {
  mockListSwapRequests.mockResolvedValue(swaps)
  const wrapper = await mountSuspended(ShiftSwapList, {
    props: { teamId: 100, canManage },
  })
  await flush()
  return wrapper
}

describe('ShiftSwapList.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('AC-8: 承諾できる PENDING 行には承諾ボタンが実際に表示される', async () => {
    const wrapper = await mount([swap({ id: 1, requesterId: 10, status: 'PENDING' })])

    expect(wrapper.find('[data-testid="swap-row-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="swap-accept-1"]').exists()).toBe(true)
  })

  it('AC-8: accepterId が null でも承諾ボタンは消えない（旧条件の回帰防止）', async () => {
    const wrapper = await mount([swap({ id: 1, accepterId: null })])
    expect(wrapper.find('[data-testid="swap-accept-1"]').exists()).toBe(true)
  })

  it('AC-8: 承諾ボタンを押すと acceptSwap が呼ばれる', async () => {
    mockAcceptSwap.mockResolvedValue({})
    const wrapper = await mount([swap({ id: 7 })])

    await wrapper.find('[data-testid="swap-accept-7"]').trigger('click')
    await flush()

    expect(mockAcceptSwap).toHaveBeenCalledWith(7)
  })

  it('AC-8/10: 申請者本人の行には承諾ボタンを出さない（BE も自己承諾を拒む）', async () => {
    const wrapper = await mount([swap({ id: 2, requesterId: CURRENT_USER_ID })])
    expect(wrapper.find('[data-testid="swap-accept-2"]').exists()).toBe(false)
  })

  it('AC-9: 管理者かつ ACCEPTED の行にだけ承認・却下ボタンが出る', async () => {
    const wrapper = await mount(
      [
        swap({ id: 3, status: 'ACCEPTED', accepterId: 30 }),
        swap({ id: 4, status: 'PENDING' }),
      ],
      true,
    )

    expect(wrapper.find('[data-testid="swap-approve-3"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="swap-reject-3"]').exists()).toBe(true)
    // PENDING の行には出ない（BE は ACCEPTED しか受け付けない）
    expect(wrapper.find('[data-testid="swap-approve-4"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="swap-reject-4"]').exists()).toBe(false)
  })

  it('AC-9: 管理者でなければ ACCEPTED でも承認・却下ボタンは出ない', async () => {
    const wrapper = await mount([swap({ id: 3, status: 'ACCEPTED', accepterId: 30 })], false)
    expect(wrapper.find('[data-testid="swap-approve-3"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="swap-reject-3"]').exists()).toBe(false)
  })

  it('AC-9: 承認・却下は大文字 APPROVE / REJECT を送る', async () => {
    mockResolveSwap.mockResolvedValue({})
    const wrapper = await mount([swap({ id: 5, status: 'ACCEPTED', accepterId: 30 })], true)

    await wrapper.find('[data-testid="swap-approve-5"]').trigger('click')
    await flush()
    expect(mockResolveSwap).toHaveBeenCalledWith(5, { action: 'APPROVE' })

    await wrapper.find('[data-testid="swap-reject-5"]').trigger('click')
    await flush()
    expect(mockResolveSwap).toHaveBeenCalledWith(5, { action: 'REJECT' })
  })

  it('AC-10: 申請者本人の PENDING 行には取り下げボタンが出て deleteSwapRequest を呼ぶ', async () => {
    mockDeleteSwapRequest.mockResolvedValue(undefined)
    const wrapper = await mount([swap({ id: 6, requesterId: CURRENT_USER_ID, status: 'PENDING' })])

    const cancelButton = wrapper.find('[data-testid="swap-cancel-6"]')
    expect(cancelButton.exists()).toBe(true)

    await cancelButton.trigger('click')
    await flush()
    expect(mockDeleteSwapRequest).toHaveBeenCalledWith(6)
  })

  it('AC-10: 申請者でない行には取り下げボタンを出さない', async () => {
    const wrapper = await mount([swap({ id: 8, requesterId: 10, status: 'PENDING' })])
    expect(wrapper.find('[data-testid="swap-cancel-8"]').exists()).toBe(false)
  })
})
