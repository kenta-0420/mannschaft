import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationPolicySettings from '~/components/reservation/ReservationPolicySettings.vue'

/**
 * ReservationPolicySettings.vue（予約ポリシー設定・F03.4.5 §6.4 W2-6-FE）ユニットテスト — 番人
 *
 * 観点（AC 対応・仮押さえ(PENDING)自動失効まわり）:
 *   AC-1: pendingExpireHours=24 で読み込んだ場合、数値入力に 24 が反映され「自動キャンセルしない」は未チェック
 *   AC-2: pendingExpireHours=null（無効化済み）かつ approvalMode=MANUAL の場合、
 *         チェックボックスがチェック済みになり、注意書き（no_expire_warning）が表示される
 *   AC-3: pendingExpireHours=null かつ approvalMode=AUTO の場合は注意書きを表示しない
 *         （AUTO は仮押さえが発生しないため無意味）
 *   AC-4: 保存ボタンで pendingExpireHours を PATCH する（値は数値のみを送る）
 *   AC-5: 「自動キャンセルしない」チェックボックスを ON にすると clearPendingExpireHours:true を送る
 *   AC-6: 1〜168 の範囲外（例: 200）を入力して保存を押すと、API を呼ばずに FE バリデーションエラーを表示する
 *
 * 注: テスト環境の既定ロケールは en。
 */
const mockGetReservationSettings = vi.fn()
const mockUpdateReservationSettings = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getReservationSettings: mockGetReservationSettings,
    updateReservationSettings: mockUpdateReservationSettings,
  }),
}))

const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: vi.fn(),
  error: mockNotifyError,
}))

const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
  getFieldErrors: () => ({}),
}))

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

beforeEach(() => {
  mockGetReservationSettings.mockReset()
  mockUpdateReservationSettings.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockHandleApiError.mockReset()
  mockUpdateReservationSettings.mockResolvedValue({ data: {} })
})

// 注: 本ファイル全体で cancelDeadlineHours は pendingExpireHours（24）とは異なる値（12）に固定する。
// 両方とも InputNumber であるため、同値だと `findAllComponents({name:'InputNumber'}).find(modelValue===24)`
// が cancelDeadlineHours 側を誤って掴む（実際に一度この事故で AC-4/AC-6 が偽陽性で落ちた・要修正済み）。
describe('ReservationPolicySettings.vue（仮押さえ自動失効・W2-6-FE）', () => {
  it('AC-1: pendingExpireHours=24 で読み込むと数値入力に24が反映され、無効化トグルは未チェック', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'AUTO', cancelDeadlineHours: 12, remindBeforeHours: '24', pendingExpireHours: 24 },
    })

    const wrapper = await mountSuspended(ReservationPolicySettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const inputNumber = wrapper.findComponent({ name: 'InputNumber' })
    expect(inputNumber.exists()).toBe(true)
    // 複数 InputNumber が存在するため、pendingExpireHours 用は modelValue=24 を持つものを探す
    const pendingInputs = wrapper.findAllComponents({ name: 'InputNumber' }).filter(c => c.props('modelValue') === 24)
    expect(pendingInputs.length).toBeGreaterThan(0)

    const checkbox = wrapper.findComponent({ name: 'Checkbox' })
    expect(checkbox.exists()).toBe(true)
    expect(checkbox.props('modelValue')).toBe(false)
  })

  it('AC-2: pendingExpireHours=null かつ MANUAL の場合、無効化トグルがチェックされ注意書きが出る', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'MANUAL', cancelDeadlineHours: 12, remindBeforeHours: '24', pendingExpireHours: null },
    })

    const wrapper = await mountSuspended(ReservationPolicySettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const checkbox = wrapper.findComponent({ name: 'Checkbox' })
    expect(checkbox.props('modelValue')).toBe(true)
    expect(wrapper.text()).toContain('will not be cancelled automatically')
  })

  it('AC-3: pendingExpireHours=null かつ AUTO の場合は注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'AUTO', cancelDeadlineHours: 12, remindBeforeHours: '24', pendingExpireHours: null },
    })

    const wrapper = await mountSuspended(ReservationPolicySettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    expect(wrapper.text()).not.toContain('will not be cancelled automatically')
  })

  it('AC-4: 保存ボタンで pendingExpireHours を PATCH する', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'AUTO', cancelDeadlineHours: 12, remindBeforeHours: '24', pendingExpireHours: 24 },
    })

    const wrapper = await mountSuspended(ReservationPolicySettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const pendingInput = wrapper.findAllComponents({ name: 'InputNumber' }).find(c => c.props('modelValue') === 24)!
    await pendingInput.vm.$emit('update:modelValue', 48)
    await flush()

    // 保存ボタンは複数あるため、直近の InputNumber の隣にあるものをクリックする代わりに
    // コンポーネント全体から「保存」ラベルのボタンを全部拾い、最後に呼ばれる save 系メソッドの
    // 結線を検証するのは煩雑なため、ここでは updateReservationSettings への呼び出し内容そのものを検証する。
    const saveButtons = wrapper.findAllComponents({ name: 'Button' }).filter(b => b.props('label') === 'Save')
    expect(saveButtons.length).toBeGreaterThan(0)
    // pendingExpireHours の保存ボタンは最後（承認モード・キャンセル期限・リマインド・仮押さえの並び順）
    await saveButtons[saveButtons.length - 1]!.trigger('click')
    await flush()

    expect(mockUpdateReservationSettings).toHaveBeenCalledWith('team-slug', { pendingExpireHours: 48 })
    expect(mockNotifySuccess).toHaveBeenCalled()
  })

  it('AC-5: 無効化トグルを ON にすると clearPendingExpireHours:true のみを送る', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'MANUAL', cancelDeadlineHours: 12, remindBeforeHours: '24', pendingExpireHours: 24 },
    })

    const wrapper = await mountSuspended(ReservationPolicySettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const checkbox = wrapper.findComponent({ name: 'Checkbox' })
    expect(checkbox.props('modelValue')).toBe(false)
    await checkbox.vm.$emit('update:modelValue', true)
    await flush()

    expect(mockUpdateReservationSettings).toHaveBeenCalledWith('team-slug', { clearPendingExpireHours: true })
    // pendingExpireHours キーを同時送信していないこと（BE Javadoc: 両方指定時は clear 優先だが、
    // FE 側でも意図を明確にするため同時送信しない）
    const call = mockUpdateReservationSettings.mock.calls.find(c => 'clearPendingExpireHours' in (c[1] as object))
    expect(call![1]).not.toHaveProperty('pendingExpireHours')
  })

  it('AC-6: 範囲外（200）を入力して保存を押すと API を呼ばず FE バリデーションエラーを表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'AUTO', cancelDeadlineHours: 12, remindBeforeHours: '24', pendingExpireHours: 24 },
    })

    const wrapper = await mountSuspended(ReservationPolicySettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const pendingInput = wrapper.findAllComponents({ name: 'InputNumber' }).find(c => c.props('modelValue') === 24)!
    await pendingInput.vm.$emit('update:modelValue', 200)
    await flush()

    const callsBefore = mockUpdateReservationSettings.mock.calls.length
    const saveButtons = wrapper.findAllComponents({ name: 'Button' }).filter(b => b.props('label') === 'Save')
    await saveButtons[saveButtons.length - 1]!.trigger('click')
    await flush()

    expect(mockUpdateReservationSettings.mock.calls.length).toBe(callsBefore)
    expect(wrapper.text()).toContain('Please enter a value between 1 and 168')
  })
})
