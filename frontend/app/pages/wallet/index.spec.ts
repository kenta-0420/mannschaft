// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { ref, shallowRef, computed, onMounted, watch } from 'vue'
import WalletPage from './index.vue'
const mocks = vi.hoisted(() => ({
  getSettings: vi.fn(),
  listCards: vi.fn(),
  listGroups: vi.fn(),
  handleApiError: vi.fn(),
}))
vi.mock('~/composables/useWalletApi', () => ({ useWalletApi: () => mocks }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => mocks }))
vi.stubGlobal('useWalletApi', () => mocks)
vi.stubGlobal('useErrorHandler', () => mocks)
vi.stubGlobal('ref', ref)
vi.stubGlobal('shallowRef', shallowRef)
vi.stubGlobal('computed', computed)
vi.stubGlobal('onMounted', onMounted)
vi.stubGlobal('watch', watch)
vi.stubGlobal('definePageMeta', vi.fn())
vi.stubGlobal('useI18n', () => ({ t: (key: string) => key }))

describe('ウォレットの独立区画と復旧（AC16）', () => {
  it('外部設定の500を同意未登録と扱わず、チーム区画を保ち、再試行でカードとグループまで回復する', async () => {
    mocks.getSettings
      .mockRejectedValueOnce({ statusCode: 500 })
      .mockResolvedValue({ isEnabled: true, termsAcceptedAt: '2026-10-16T12:00:00' })
    mocks.listCards.mockResolvedValue([])
    mocks.listGroups.mockResolvedValue([])
    const wrapper = mount(WalletPage, {
      global: {
        plugins: [
          createI18n({ legacy: false, locale: 'ja', missingWarn: false, fallbackWarn: false }),
        ],
        stubs: {
          LoyaltyWalletPanel: { template: '<div data-testid="team-points" />' },
          DashboardErrorState: {
            emits: ['retry'],
            template:
              '<div data-testid="external-error"><button @click="$emit(\'retry\')">Retry</button></div>',
          },
          TermsAcceptModal: {
            props: ['modelValue'],
            template: '<div data-testid="terms" :data-open="String(modelValue)" />',
          },
          PageHeader: true,
          PageLoading: true,
          SectionCard: true,
          InputText: true,
          Button: true,
          CardTile: true,
          GroupTile: true,
          NuxtLink: true,
          WalletGuideModal: true,
        },
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="team-points"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="external-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="terms"]').attributes('data-open')).toBe('false')
    expect(mocks.listCards).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="external-error"] button').trigger('click')
    await flushPromises()
    expect(mocks.getSettings).toHaveBeenCalledTimes(2)
    expect(mocks.listCards).toHaveBeenCalledTimes(1)
    expect(mocks.listGroups).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="external-error"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
