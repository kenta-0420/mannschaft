import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import MarketEscrowConfirmDialog from './MarketEscrowConfirmDialog.vue'

const getRecruitmentPaymentIntent = vi.fn()
const mountPaymentElement = vi.fn()
const confirmPayment = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useMarketPaymentApi', () => () => ({ getRecruitmentPaymentIntent }))
mockNuxtImport('useStripeSetup', () => () => ({ mountPaymentElement, confirmPayment }))

const stubs = {
  Dialog: {
    props: ['visible'],
    emits: ['update:visible'],
    template: '<section v-if="visible" role="dialog"><slot /><slot name="footer" /></section>',
  },
  Button: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  LoadingBounce: true,
}

function paymentView(status: string, clientSecret: string | null) {
  return {
    data: {
      clientSecret,
      escrowTransactionId: '00000000-0000-0000-0000-000000000001',
      status,
      faceAmount: 1000,
      chargeAmount: 1025,
      applicationFeeAmount: 50,
    },
  }
}

beforeEach(() => {
  getRecruitmentPaymentIntent.mockReset()
  mountPaymentElement.mockReset()
  confirmPayment.mockReset()
  mountPaymentElement.mockResolvedValue({
    stripe: {},
    elements: {},
    unmount: vi.fn(),
  })
})

describe('MarketEscrowConfirmDialog', () => {
  it('AUTHORIZED でも clientSecret があれば即時徴収用 PaymentElement を表示する', async () => {
    getRecruitmentPaymentIntent.mockResolvedValue(paymentView('AUTHORIZED', 'pi_secret'))

    const wrapper = await mountSuspended(MarketEscrowConfirmDialog, {
      props: { visible: true, listingId: 101, participantId: 202 },
      global: { stubs },
    })
    await flushPromises()

    expect(mountPaymentElement).toHaveBeenCalledWith('pi_secret', expect.any(String))
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('market.payment.confirm.submit')
  })

  it('clientSecret のない AUTHORIZED は確認済みとして再 confirm しない', async () => {
    getRecruitmentPaymentIntent.mockResolvedValue(paymentView('AUTHORIZED', null))

    const wrapper = await mountSuspended(MarketEscrowConfirmDialog, {
      props: { visible: true, listingId: 101, participantId: 202 },
      global: { stubs },
    })
    await flushPromises()

    expect(mountPaymentElement).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('market.payment.confirm.alreadyAuthorized')
  })
})
