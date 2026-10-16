<script setup lang="ts">
import type { PaymentRequestResponse, PaymentRequestStatus } from '~/types/paymentRequest'

definePageMeta({ middleware: 'auth', layout: 'default' })

const POLL_INTERVAL_MS = 1000
const MAX_POLL_ATTEMPTS = 20

const route = useRoute()
const { t, locale } = useI18n()
const paymentRequestApi = usePaymentRequestApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const teamSlug = computed(() => String(route.params.slug))
const requestId = computed(() => String(route.params.id))
const { data: request, teamId, loading, isAdminOrDeputy, state, loadError, load, fail } = useTeamPaymentPage(
  teamSlug,
  async (id) => (await paymentRequestApi.getTeamPaymentRequest(id, requestId.value)).data,
  () => null as PaymentRequestResponse | null,
  requestId,
)
const startingPayment = ref(false)
const polling = ref(false)
const clientSecret = ref<string | null>(null)
const paymentDialogVisible = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let unmounted = false
let paymentGeneration = 0

const returnUrl = computed(() => import.meta.client ? window.location.href : '')
const canPay = computed(() => isAdminOrDeputy.value && ['SENT', 'VIEWED', 'OVERDUE'].includes(request.value?.status ?? ''))

function amountLabel(): string {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency', currency: request.value?.currency ?? 'JPY',
  }).format(request.value?.faceAmount ?? 0)
}

function statusSeverity(status: PaymentRequestStatus | undefined) {
  if (status === 'PAID') return 'success'
  if (status === 'OVERDUE') return 'danger'
  if (status === 'PROCESSING' || status === 'VIEWED') return 'info'
  if (status === 'CANCELLED') return 'secondary'
  return 'warn'
}

function statusLabel(status: PaymentRequestStatus | undefined): string {
  const key = status === 'VIEWED' ? 'statusViewed'
    : status === 'PROCESSING' ? 'statusProcessing'
      : status === 'CANCELLED' ? 'statusCancelled'
        : status === 'PAID' ? 'statusPaid'
          : status === 'OVERDUE' ? 'statusOverdue'
            : status === 'SENT' ? 'statusSent' : 'statusDraft'
  return t(`payment.membership.paymentRequest.${key}`)
}

function stopPolling(): void {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
  polling.value = false
}

async function fetchDetail(): Promise<PaymentRequestResponse | null> {
  if (!teamId.value || !isAdminOrDeputy.value) return null
  const id = teamId.value
  const slug = teamSlug.value
  const detailId = requestId.value
  const generation = paymentGeneration
  const isCurrent = () => generation === paymentGeneration && !unmounted && slug === teamSlug.value && detailId === requestId.value && id === teamId.value
  try {
    const response = await paymentRequestApi.getTeamPaymentRequest(id, detailId)
    if (!isCurrent()) return null
    request.value = response.data
    return response.data
  } catch (error: unknown) {
    if (isCurrent()) {
      fail(error)
      stopPolling()
    }
    return null
  }
}

function clearStripeReturnQuery(): void {
  if (!import.meta.client) return
  const url = new URL(window.location.href)
  const stripeKeys = ['payment_intent', 'payment_intent_client_secret', 'redirect_status']
  if (!stripeKeys.some((key) => url.searchParams.has(key))) return
  stripeKeys.forEach((key) => url.searchParams.delete(key))
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

async function startPayment(): Promise<void> {
  if (!teamId.value || !canPay.value || startingPayment.value) return
  if (!isAdminOrDeputy.value) return
  const slug = teamSlug.value
  const detailId = requestId.value
  const id = teamId.value
  const generation = paymentGeneration
  const isCurrent = () => generation === paymentGeneration && !unmounted && slug === teamSlug.value && detailId === requestId.value && id === teamId.value
  startingPayment.value = true
  try {
    const response = await paymentRequestApi.payPaymentRequest(id, detailId)
    if (!isCurrent()) return
    if (!response.data.clientSecret) throw new Error('payment request client secret is missing')
    clientSecret.value = response.data.clientSecret
    if (request.value) request.value = { ...request.value, status: 'PROCESSING' }
    paymentDialogVisible.value = true
  } catch (error: unknown) {
    if (!isCurrent()) return
    const detail = await fetchDetail()
    if (teamId.value && detail && detail.status !== 'PROCESSING') {
      paymentRequestApi.clearPaymentRequestIdempotencyKey(teamId.value, requestId.value)
    }
    handleApiError(error, 'payment-request.team.pay')
  } finally {
    if (isCurrent()) startingPayment.value = false
  }
}

async function resumePayment(): Promise<void> {
  if (!teamId.value || request.value?.status !== 'PROCESSING' || startingPayment.value) return
  if (!isAdminOrDeputy.value) return
  const slug = teamSlug.value
  const detailId = requestId.value
  const id = teamId.value
  const generation = paymentGeneration
  const isCurrent = () => generation === paymentGeneration && !unmounted && slug === teamSlug.value && detailId === requestId.value && id === teamId.value
  startingPayment.value = true
  try {
    const response = await paymentRequestApi.payPaymentRequest(id, detailId)
    if (!isCurrent()) return
    if (!response.data.clientSecret) throw new Error('payment request client secret is missing')
    clientSecret.value = response.data.clientSecret
    paymentDialogVisible.value = true
  } catch (error: unknown) {
    if (!isCurrent()) return
    fail(error)
  } finally {
    if (isCurrent()) startingPayment.value = false
  }
}

function onPaymentDialogVisible(visible: boolean): void {
  paymentDialogVisible.value = visible
  if (!visible) clientSecret.value = null
}

async function pollPaymentStatus(attempt = 1): Promise<void> {
  if (unmounted || !polling.value) return
  const updated = await fetchDetail()
  if (unmounted || !polling.value) return
  if (updated?.status === 'PAID') {
    stopPolling()
    if (teamId.value) paymentRequestApi.clearPaymentRequestIdempotencyKey(teamId.value, requestId.value)
    notification.success(t('payment.membership.paymentRequest.paid'))
    return
  }
  if (updated && updated.status !== 'PROCESSING') {
    stopPolling()
    if (teamId.value) paymentRequestApi.clearPaymentRequestIdempotencyKey(teamId.value, requestId.value)
    notification.error(t('payment.membership.paymentRequest.payError'))
    return
  }
  if (attempt >= MAX_POLL_ATTEMPTS) {
    stopPolling()
    notification.warn(t('payment.membership.paymentRequest.processing'))
    return
  }
  pollTimer = setTimeout(() => void pollPaymentStatus(attempt + 1), POLL_INTERVAL_MS)
}

function startPolling(): void {
  if (polling.value) return
  polling.value = true
  void pollPaymentStatus()
}

watch(() => [teamSlug.value, requestId.value], () => {
  paymentGeneration += 1
  stopPolling()
  paymentDialogVisible.value = false
  clientSecret.value = null
  startingPayment.value = false
}, { flush: 'sync' })
watch([loading, request], ([isLoading, detail]) => {
  if (isLoading || !detail) {
    stopPolling()
    paymentDialogVisible.value = false
    clientSecret.value = null
  } else if (detail.status === 'PROCESSING') startPolling()
})
onMounted(clearStripeReturnQuery)
onUnmounted(() => {
  unmounted = true
  paymentGeneration += 1
  stopPolling()
})
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <PageHeader :title="request?.title || t('payment.membership.paymentRequest.title')" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="state === 'error'" :error="loadError" :show-retry="true" @retry="load" />
    <Message v-else-if="state === 'denied'" severity="error" :closable="false">
      {{ t('payment.admin.permissionDenied') }}
    </Message>
    <DashboardErrorState v-else-if="!request" :message="t('payment.membership.paymentRequest.loadError')" @retry="load" />
    <SectionCard v-else :title="request.title || '-'">
      <div class="flex flex-col gap-4">
        <div class="flex items-center justify-between gap-3">
          <span class="text-2xl font-bold">{{ amountLabel() }}</span>
          <Tag
            data-testid="payment-request-status"
            :data-status="request.status"
            :value="statusLabel(request.status)"
            :severity="statusSeverity(request.status)"
          />
        </div>
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.due') }}</dt>
          <dd>{{ request.dueDate || '-' }}</dd>
          <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.from') }}</dt>
          <dd>{{ request.organizationId || '-' }}</dd>
        </dl>
        <p v-if="request.description" class="whitespace-pre-wrap break-words text-sm text-surface-700 dark:text-surface-200">{{ request.description }}</p>
        <Message v-if="request.status === 'OVERDUE'" severity="warn" :closable="false">{{ t('payment.membership.paymentRequest.statusOverdue') }}</Message>
        <Message v-if="request.status === 'PROCESSING' || polling" severity="info" :closable="false">{{ t('payment.membership.paymentRequest.processing') }}</Message>
        <div class="flex flex-wrap justify-end gap-2">
          <Button :label="t('button.back')" severity="secondary" outlined as="router-link" :to="`/teams/${teamSlug}/payment-requests`" />
          <Button
            v-if="canPay || request.status === 'PROCESSING'"
            :label="t('payment.membership.paymentRequest.pay')"
            icon="pi pi-credit-card"
            :loading="startingPayment"
            :disabled="startingPayment"
            data-testid="payment-request-pay"
            @click="canPay ? startPayment() : resumePayment()"
          />
        </div>
      </div>
    </SectionCard>
    <PaymentRequestPaymentDialog
      v-if="isAdminOrDeputy"
      :visible="paymentDialogVisible"
      :client-secret="clientSecret"
      :return-url="returnUrl"
      @update:visible="onPaymentDialogVisible"
      @confirmed="startPolling"
    />
  </div>
</template>
