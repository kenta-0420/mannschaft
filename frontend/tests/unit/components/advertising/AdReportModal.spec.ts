import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import AdReportModal from '~/components/advertising/AdReportModal.vue'

/**
 * F09.17 AdReportModal.vue ユニットテスト
 *
 * テスト観点:
 *   ARM-001: visible=true でダイアログが描画される
 *   ARM-002: reason 未選択時は createReport が呼ばれない
 *   ARM-003: reason 選択 + 送信 → createReport を所定の引数で呼ぶ + 成功トースト
 *   ARM-004: 送信成功で submitted emit + visible=false emit
 *   ARM-005: 429 受信時はレート制限警告トースト
 */

const mockCreateReport = vi.fn()
vi.mock('~/composables/useAdDeliveriesApi', () => ({
  useAdDeliveriesApi: () => ({
    listDeliveries: vi.fn(),
    deleteAllDeliveries: vi.fn(),
    createReport: mockCreateReport,
  }),
}))

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
const mockToastWarn = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockToastSuccess,
    info: vi.fn(),
    warn: mockToastWarn,
    error: mockToastError,
  }),
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockCreateReport.mockReset()
  mockToastSuccess.mockReset()
  mockToastError.mockReset()
  mockToastWarn.mockReset()
})

afterEach(() => {
  // PrimeVue Dialog のテレポート先 (body) をクリーンアップ
  document.body
    .querySelectorAll('.p-dialog')
    .forEach((el) => el.parentElement?.removeChild(el))
})

describe('AdReportModal.vue', () => {
  it('ARM-001: visible=true でダイアログが描画される', async () => {
    await mountSuspended(AdReportModal, {
      props: {
        visible: true,
        campaignId: '0190xxxxxxxxxxxxxxxxxxxxxxxxxxxx',
      },
    })
    // PrimeVue Dialog は body へテレポート
    expect(document.body.querySelector('.p-dialog')).not.toBeNull()
  })

  it('ARM-002: reason 未選択時は createReport が呼ばれない', async () => {
    const wrapper = await mountSuspended(AdReportModal, {
      props: {
        visible: true,
        campaignId: 'cmp-1',
      },
    })
    const vm = wrapper.vm as unknown as { handleSubmit: () => Promise<void> }
    await vm.handleSubmit()
    expect(mockCreateReport).not.toHaveBeenCalled()
  })

  it('ARM-003: reason 選択 + 送信 → createReport を所定引数で呼ぶ + success トースト', async () => {
    // F09.19.9: レスポンスは { id, status, createdAt }
    mockCreateReport.mockResolvedValueOnce({
      data: {
        id: 'rep-1',
        status: 'NEW',
        createdAt: '2026-05-17T00:00:00Z',
      },
    })
    const wrapper = await mountSuspended(AdReportModal, {
      props: {
        visible: true,
        campaignId: 'cmp-1',
      },
    })
    const vm = wrapper.vm as unknown as {
      selectedReason: string | null
      detail: string
      handleSubmit: () => Promise<void>
    }
    vm.selectedReason = 'MISLEADING'
    vm.detail = '実態と異なる'
    await vm.handleSubmit()

    // F09.19.9: BE 契約（campaignId / operationalCampaignId の XOR + channelType + reasonCode + comment）
    expect(mockCreateReport).toHaveBeenCalledWith({
      campaignId: 'cmp-1',
      operationalCampaignId: undefined,
      channelType: 'BANNER',
      reasonCode: 'MISLEADING',
      comment: '実態と異なる',
    })
    expect(mockToastSuccess).toHaveBeenCalled()
  })

  it('ARM-004: 送信成功で submitted emit + update:visible(false) emit', async () => {
    mockCreateReport.mockResolvedValueOnce({
      data: {
        id: 'rep-1',
        status: 'NEW',
        createdAt: '',
      },
    })
    const wrapper = await mountSuspended(AdReportModal, {
      props: {
        visible: true,
        campaignId: 'cmp-1',
      },
    })
    const vm = wrapper.vm as unknown as {
      selectedReason: string | null
      handleSubmit: () => Promise<void>
    }
    vm.selectedReason = 'SPAM'
    await vm.handleSubmit()

    const submittedEvents = wrapper.emitted('submitted')
    const visibleEvents = wrapper.emitted('update:visible')
    expect(submittedEvents).toBeTruthy()
    expect(submittedEvents?.length).toBe(1)
    expect(visibleEvents).toBeTruthy()
    expect(visibleEvents?.some((args) => args[0] === false)).toBe(true)
  })

  it('ARM-005: 429 受信時はレート制限警告トースト', async () => {
    mockCreateReport.mockRejectedValueOnce({
      statusCode: 429,
      message: 'Too Many Requests',
    })
    const wrapper = await mountSuspended(AdReportModal, {
      props: {
        visible: true,
        campaignId: 'cmp-1',
      },
    })
    const vm = wrapper.vm as unknown as {
      selectedReason: string | null
      handleSubmit: () => Promise<void>
    }
    vm.selectedReason = 'OFFENSIVE'
    await vm.handleSubmit()

    expect(mockToastWarn).toHaveBeenCalled()
    expect(mockToastSuccess).not.toHaveBeenCalled()
  })
})
