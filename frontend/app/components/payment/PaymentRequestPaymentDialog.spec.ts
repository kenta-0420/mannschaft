import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import PaymentRequestPaymentDialog from './PaymentRequestPaymentDialog.vue'

const mountPaymentElement = vi.fn()
const confirmPayment = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useStripeSetup', () => () => ({ mountPaymentElement, confirmPayment }))

const stubs = {
  Dialog: {
    props: ['visible'],
    template: '<section v-if="visible" role="dialog"><slot /><slot name="footer" /></section>',
  },
  Button: {
    props: ['disabled', 'label'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  LoadingBounce: true,
}

beforeEach(() => {
  mountPaymentElement.mockReset()
  confirmPayment.mockReset()
  mountPaymentElement.mockResolvedValue({ stripe: { id: 'stripe' }, elements: { id: 'elements' }, unmount: vi.fn() })
})

describe('PaymentRequestPaymentDialog', () => {
  it('client secret を永続化せず PaymentElement に渡し、閉じると unmount する', async () => {
    const unmount = vi.fn()
    mountPaymentElement.mockResolvedValue({ stripe: {}, elements: {}, unmount })
    const wrapper = await mountSuspended(PaymentRequestPaymentDialog, {
      props: { visible: true, clientSecret: 'pi_secret', returnUrl: 'https://example.test/payment-return' },
      global: { stubs },
    })
    await flushPromises()

    expect(mountPaymentElement).toHaveBeenCalledWith('pi_secret', expect.any(String))
    await wrapper.setProps({ visible: false })
    expect(unmount).toHaveBeenCalledTimes(1)
  })

  it('confirmPayment に 3DS returnUrl を渡し、成功時だけ polling 開始用イベントを emit する', async () => {
    confirmPayment.mockResolvedValue({ status: 'succeeded', paymentIntentStatus: 'requires_capture' })
    const wrapper = await mountSuspended(PaymentRequestPaymentDialog, {
      props: { visible: true, clientSecret: 'pi_secret', returnUrl: 'https://example.test/payment-return' },
      global: { stubs },
    })
    await flushPromises()

    await wrapper.get('[data-testid="payment-request-confirm-button"]').trigger('click')

    expect(confirmPayment).toHaveBeenCalledWith(expect.objectContaining({
      returnUrl: 'https://example.test/payment-return',
    }))
    expect(wrapper.emitted('confirmed')).toEqual([['requires_capture']])
    expect(wrapper.emitted('update:visible')).toContainEqual([false])
  })

  it('送信中の連打は confirmPayment を重複実行しない', async () => {
    let resolveConfirmation: (result: { status: 'succeeded'; paymentIntentStatus: string }) => void
    confirmPayment.mockReturnValue(new Promise((resolve) => { resolveConfirmation = resolve }))
    const wrapper = await mountSuspended(PaymentRequestPaymentDialog, {
      props: { visible: true, clientSecret: 'pi_secret', returnUrl: 'https://example.test/payment-return' },
      global: { stubs },
    })
    await flushPromises()
    const button = wrapper.get('[data-testid="payment-request-confirm-button"]')

    await button.trigger('click')
    await button.trigger('click')
    expect(confirmPayment).toHaveBeenCalledTimes(1)
    resolveConfirmation!({ status: 'succeeded', paymentIntentStatus: 'requires_capture' })
    await flushPromises()
  })
})
