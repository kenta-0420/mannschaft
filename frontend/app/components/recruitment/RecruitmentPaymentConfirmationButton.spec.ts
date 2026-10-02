import { afterEach, describe, expect, it } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import RecruitmentPaymentConfirmationButton from './RecruitmentPaymentConfirmationButton.vue'

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const stubs = {
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button data-testid="payment-button" @click="$emit(\'click\')">{{ label }}</button>',
  },
  MarketEscrowConfirmDialog: {
    props: ['visible', 'listingId', 'participantId'],
    emits: ['update:visible', 'confirmed'],
    template: `
      <div v-if="visible" data-testid="payment-dialog">
        <span>{{ listingId }}:{{ participantId }}</span>
        <button data-testid="confirmed" @click="$emit('confirmed', 'escrow-id')">confirmed</button>
      </div>
    `,
  },
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('RecruitmentPaymentConfirmationButton', () => {
  it('応募直後は支払者本人の募集IDと参加IDで確認ダイアログを自動表示する', async () => {
    const wrapper = await mountSuspended(RecruitmentPaymentConfirmationButton, {
      props: { listingId: 101, participantId: 202, autoOpen: true },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="payment-dialog"]').text()).toContain('101:202')
  })

  it('再訪時はボタンから確認を再開でき、Stripe確認完了を親へ通知する', async () => {
    const wrapper = await mountSuspended(RecruitmentPaymentConfirmationButton, {
      props: { listingId: 101, participantId: 202 },
      global: { stubs },
    })

    expect(wrapper.find('[data-testid="payment-dialog"]').exists()).toBe(false)
    await wrapper.get('[data-testid="recruitment-payment-confirm-button"]').trigger('click')
    await wrapper.get('[data-testid="confirmed"]').trigger('click')

    expect(wrapper.emitted('confirmed')).toEqual([['escrow-id']])
  })
})
