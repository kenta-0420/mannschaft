import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import RecruitmentApplicationButton from './RecruitmentApplicationButton.vue'
import type { RecruitmentListingResponse, RecruitmentParticipantResponse } from '~/types/recruitment'

const applyToListing = vi.fn()
const cancelMyApplication = vi.fn()
const estimateCancellationFee = vi.fn()
const notificationSuccess = vi.fn()
const notificationError = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRecruitmentApi', () => () => ({
  applyToListing,
  cancelMyApplication,
  estimateCancellationFee,
}))
mockNuxtImport('useNotification', () => () => ({
  success: notificationSuccess,
  error: notificationError,
}))

const stubs = {
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')">{{ label }}</button>',
  },
  RecruitmentCancellationConfirmModal: true,
  RecruitmentPaymentConfirmationButton: {
    props: ['listingId', 'participantId', 'autoOpen'],
    template: '<div data-testid="payer-confirm" :data-listing="listingId" :data-participant="participantId" :data-auto-open="autoOpen" />',
  },
}

const listing: RecruitmentListingResponse = {
  id: 101,
  scopeType: 'TEAM',
  scopeId: '1',
  categoryId: 9,
  categoryNameI18nKey: null,
  subcategoryId: null,
  subcategoryName: null,
  title: '有料募集',
  description: null,
  participationType: 'INDIVIDUAL',
  startAt: '2026-08-30T10:00:00',
  endAt: '2026-08-30T11:00:00',
  applicationDeadline: '2026-08-30T09:00:00',
  autoCancelAt: '2026-08-30T08:00:00',
  capacity: 5,
  minCapacity: 1,
  confirmedCount: 0,
  waitlistCount: 0,
  waitlistMax: 5,
  paymentEnabled: true,
  price: 1000,
  payeeKind: 'TEAM',
  payeeUserId: null,
  visibility: 'PUBLIC',
  status: 'OPEN',
  location: null,
  reservationLineId: null,
  imageUrl: null,
  cancellationPolicyId: null,
  createdBy: 1,
  cancelledAt: null,
  cancelledBy: null,
  cancelledReason: null,
  createdAt: '2026-08-29T10:00:00',
  updatedAt: '2026-08-29T10:00:00',
}

function participant(status: RecruitmentParticipantResponse['status']): RecruitmentParticipantResponse {
  return {
    id: 202,
    listingId: 101,
    participantType: 'USER',
    userId: 2,
    teamId: null,
    appliedBy: 2,
    status,
    waitlistPosition: status === 'WAITLISTED' ? 1 : null,
    note: null,
    appliedAt: '2026-08-29T10:00:00',
    statusChangedAt: '2026-08-29T10:00:00',
  }
}

beforeEach(() => {
  applyToListing.mockReset()
  cancelMyApplication.mockReset()
  estimateCancellationFee.mockReset()
  notificationSuccess.mockReset()
  notificationError.mockReset()
})

describe('RecruitmentApplicationButton', () => {
  it('有料募集への確定申込後に応募者本人のPaymentElement導線を自動表示する', async () => {
    applyToListing.mockResolvedValue({ data: participant('CONFIRMED') })
    const wrapper = await mountSuspended(RecruitmentApplicationButton, {
      props: { listing },
      global: { stubs },
    })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    const confirmation = wrapper.get('[data-testid="payer-confirm"]')
    expect(confirmation.attributes('data-listing')).toBe('101')
    expect(confirmation.attributes('data-participant')).toBe('202')
    expect(confirmation.attributes('data-auto-open')).toBe('true')
  })

  it('確定済み応募では再訪用の支払い確認導線を表示し、キャンセル待ちでは表示しない', async () => {
    const confirmed = await mountSuspended(RecruitmentApplicationButton, {
      props: { listing, myParticipantId: 202, myParticipantStatus: 'CONFIRMED' },
      global: { stubs },
    })
    expect(confirmed.find('[data-testid="payer-confirm"]').exists()).toBe(true)

    const waitlisted = await mountSuspended(RecruitmentApplicationButton, {
      props: { listing, myParticipantId: 203, myParticipantStatus: 'WAITLISTED' },
      global: { stubs },
    })
    expect(waitlisted.find('[data-testid="payer-confirm"]').exists()).toBe(false)
  })
})
