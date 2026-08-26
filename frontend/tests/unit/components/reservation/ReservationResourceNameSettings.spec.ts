import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationResourceNameSettings from '~/components/reservation/ReservationResourceNameSettings.vue'

/**
 * ReservationResourceNameSettings.vue（呼称設定UI・F03.4.5 §5.1）ユニットテスト — 番人
 *
 * 観点（予約v2 W2-3-FE 受け入れ条件対応）:
 *   AC-N1: プリセット（SEAT）選択→保存で updateReservationSettings に resourceNameType:'SEAT' が渡る
 *   AC-N2: 未設定（DEFAULT）は「Bookable Item」相当のフォールバック表示（既存挙動と一致）
 *   AC-N3: CUSTOM選択かつ自由入力が空のまま保存しようとすると API を呼ばずブロックする（400を待たない）。
 *          CUSTOM以外では resourceNameCustom を送らない（BEのNULL正規化に委ねる）。
 *          自由入力欄は maxlength=30（31文字入力を FE で抑止）。
 *   AC-N6: disabled=true（非ADMIN）では Select・保存ボタンとも disabled
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
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
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
  mockHandleApiError.mockReset()
})

describe('ReservationResourceNameSettings.vue', () => {
  it('AC-N2: resourceNameType 未設定（フィールドなし）は DEFAULT として扱われる', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: {} })

    const wrapper = await mountSuspended(ReservationResourceNameSettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const select = wrapper.find('[data-testid="resource-name-type-select"]')
    expect(select.exists()).toBe(true)
    // PrimeVue Select は現在値をラベルテキストとして描画する
    expect(wrapper.text()).toContain('Bookable Item')
  })

  it('AC-N1: SEAT を選択して保存すると updateReservationSettings に resourceNameType: "SEAT" が渡る', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'DEFAULT' } })
    mockUpdateReservationSettings.mockResolvedValue({ data: { resourceNameType: 'SEAT', resourceNameCustom: null } })

    const wrapper = await mountSuspended(ReservationResourceNameSettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    // vm 経由で内部状態を直接更新（PrimeVue Select の実クリック操作はJSDOM上で複雑なため、
    // 既存 ReservationPolicySettings.spec 系と同様に vm 経由のトリガーで契約を検証する）。
    const vm = wrapper.vm as unknown as { resourceNameType: string }
    vm.resourceNameType = 'SEAT'
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="resource-name-save"]').trigger('click')
    await flush()

    expect(mockUpdateReservationSettings).toHaveBeenCalledTimes(1)
    const [teamId, body] = mockUpdateReservationSettings.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    expect(body.resourceNameType).toBe('SEAT')
    // CUSTOM 以外では resourceNameCustom を送らない
    expect(body.resourceNameCustom).toBeUndefined()
    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
  })

  it('AC-N1: CUSTOM を選択し自由入力「施術台」で保存すると resourceNameCustom がそのまま渡る', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'DEFAULT' } })
    mockUpdateReservationSettings.mockResolvedValue({
      data: { resourceNameType: 'CUSTOM', resourceNameCustom: '施術台' },
    })

    const wrapper = await mountSuspended(ReservationResourceNameSettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const vm = wrapper.vm as unknown as { resourceNameType: string; resourceNameCustom: string }
    vm.resourceNameType = 'CUSTOM'
    await wrapper.vm.$nextTick()
    vm.resourceNameCustom = '施術台'
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="resource-name-save"]').trigger('click')
    await flush()

    expect(mockUpdateReservationSettings).toHaveBeenCalledTimes(1)
    const [, body] = mockUpdateReservationSettings.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.resourceNameType).toBe('CUSTOM')
    expect(body.resourceNameCustom).toBe('施術台')
  })

  it('AC-N3: CUSTOM選択かつ自由入力が空のまま保存しようとすると API を呼ばず custom_required を表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'DEFAULT' } })

    const wrapper = await mountSuspended(ReservationResourceNameSettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const vm = wrapper.vm as unknown as { resourceNameType: string }
    vm.resourceNameType = 'CUSTOM'
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="resource-name-save"]').trigger('click')
    await flush()

    expect(mockUpdateReservationSettings).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="resource-name-custom-required"]').exists()).toBe(true)
  })

  it('AC-N3: 自由入力欄は maxlength=30（31文字入力の抑止をFEで保証する）', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'CUSTOM', resourceNameCustom: 'あ' } })

    const wrapper = await mountSuspended(ReservationResourceNameSettings, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const input = wrapper.find('[data-testid="resource-name-custom-input"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('maxlength')).toBe('30')
  })

  it('AC-N6: disabled=true（非ADMIN）では Select・保存ボタンが disabled になる', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'DEFAULT' } })

    const wrapper = await mountSuspended(ReservationResourceNameSettings, {
      props: { teamId: 'team-slug', disabled: true },
    })
    await flush()

    const select = wrapper.findComponent({ name: 'Select' })
    expect(select.exists()).toBe(true)
    expect(select.props('disabled')).toBe(true)

    const saveButton = wrapper.find('[data-testid="resource-name-save"]')
    expect(saveButton.attributes('disabled')).toBeDefined()
  })
})
