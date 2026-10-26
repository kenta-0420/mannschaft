import type { components } from '~/types/generated'

/** P7 支払依頼 API の生成型を画面層向けに公開する。 */
export type PaymentRequestResponse = components['schemas']['PaymentRequestResponse']
export type PaymentRequestPayResponse = components['schemas']['PaymentRequestPayResponse']
export type PaymentRequestPageResponse = components['schemas']['PagedResponsePaymentRequestResponse']
export type PaymentRequestListResponse = components['schemas']['ApiResponseListPaymentRequestResponse']
export type CreatePaymentRequestRequest = components['schemas']['CreatePaymentRequestRequest']
export type TeamPaymentAdvanceResponse = components['schemas']['TeamPaymentAdvanceResponse']

/** webhook 確定前の PROCESSING を含む支払依頼の状態。 */
export type PaymentRequestStatus = NonNullable<PaymentRequestResponse['status']>
