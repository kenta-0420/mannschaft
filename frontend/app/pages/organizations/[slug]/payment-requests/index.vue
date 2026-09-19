<script setup lang="ts">
import type { PaymentRequestResponse, PaymentRequestStatus } from '~/types/paymentRequest'

definePageMeta({ middleware: 'auth', layout: 'default' })

const route = useRoute()
const { t, locale } = useI18n()
const { isAdminOrDeputy } = useOrgShellContext()
const { resolveScopeId } = useActivityScopeId()
const paymentRequestApi = usePaymentRequestApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const confirm = useConfirm()

const orgSlug = computed(() => String(route.params.slug))
const orgId = ref<number | null>(null)
const requests = ref<PaymentRequestResponse[]>([])
const loading = ref(true)
const actingId = ref<string | null>(null)
const statusFilter = ref<PaymentRequestStatus | null>(null)

function amountLabel(request: PaymentRequestResponse): string {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: request.currency ?? 'JPY',
  }).format(request.faceAmount ?? 0)
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

async function load(): Promise<void> {
  loading.value = true
  try {
    orgId.value = await resolveScopeId('ORGANIZATION', orgSlug.value)
    if (orgId.value === null) return
    const response = await paymentRequestApi.listOrganizationPaymentRequests(orgId.value, { status: statusFilter.value ?? undefined })
    requests.value = response.data ?? []
  } catch (error: unknown) {
    handleApiError(error, 'payment-request.organization.list')
  } finally {
    loading.value = false
  }
}

async function runAction(request: PaymentRequestResponse, action: 'send' | 'cancel'): Promise<void> {
  if (!orgId.value || !request.id || actingId.value) return
  actingId.value = request.id
  try {
    if (action === 'send') {
      await paymentRequestApi.sendPaymentRequest(orgId.value, request.id)
      notification.success(t('payment.membership.paymentRequest.sent'))
    } else {
      await paymentRequestApi.cancelPaymentRequest(orgId.value, request.id)
      notification.success(t('payment.membership.paymentRequest.cancelled'))
    }
    await load()
  } catch (error: unknown) {
    handleApiError(error, `payment-request.organization.${action}`)
  } finally {
    actingId.value = null
  }
}

function confirmAction(request: PaymentRequestResponse, action: 'send' | 'cancel'): void {
  confirm.require({
    message: t('payment.membership.paymentRequest.confirm'),
    header: t(`payment.membership.paymentRequest.${action}`),
    acceptLabel: t('button.confirm'),
    rejectLabel: t('button.cancel'),
    accept: () => void runAction(request, action),
  })
}

onMounted(load)
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <ConfirmDialog />
    <PageHeader
      :title="t('payment.membership.paymentRequest.adminTitle')"
      :description="t('payment.membership.paymentRequest.adminDescription')"
    />

    <PageLoading v-if="loading" />
    <Message v-else-if="!isAdminOrDeputy" severity="error" :closable="false">
      {{ t('payment.admin.permissionDenied') }}
    </Message>
    <Message v-else-if="orgId === null" severity="error" :closable="false">
      {{ t('payment.membership.paymentRequest.loadError') }}
    </Message>
    <template v-else>
      <div class="flex flex-wrap justify-between gap-2">
        <Select
          v-model="statusFilter"
          :options="[
            { label: t('payment.membership.paymentRequest.statusDraft'), value: 'DRAFT' },
            { label: t('payment.membership.paymentRequest.statusSent'), value: 'SENT' },
            { label: t('payment.membership.paymentRequest.statusViewed'), value: 'VIEWED' },
            { label: t('payment.membership.paymentRequest.statusProcessing'), value: 'PROCESSING' },
            { label: t('payment.membership.paymentRequest.statusPaid'), value: 'PAID' },
            { label: t('payment.membership.paymentRequest.statusOverdue'), value: 'OVERDUE' },
            { label: t('payment.membership.paymentRequest.statusCancelled'), value: 'CANCELLED' },
          ]"
          option-label="label"
          option-value="value"
          show-clear
          class="w-full sm:w-56"
          @change="load"
        />
        <Button
          :label="t('payment.membership.paymentRequest.create')"
          icon="pi pi-plus"
          as="router-link"
          :to="`/organizations/${orgSlug}/payment-requests/new`"
        />
      </div>
      <DashboardEmptyState
        v-if="requests.length === 0"
        icon="pi pi-file"
        :message="t('payment.membership.paymentRequest.empty')"
      />
      <div v-else class="grid gap-3">
        <SectionCard v-for="request in requests" :key="request.id" :title="request.title || '-'">
          <div class="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <dl class="grid min-w-0 grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
              <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.amount') }}</dt>
              <dd class="font-semibold">{{ amountLabel(request) }}</dd>
              <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.due') }}</dt>
              <dd>{{ request.dueDate || '-' }}</dd>
              <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.team') }}</dt>
              <dd>{{ request.payerScopeId || '-' }}</dd>
            </dl>
            <Tag
              data-testid="payment-request-status"
              :data-status="request.status"
              :value="statusLabel(request.status)"
              :severity="statusSeverity(request.status)"
            />
          </div>
          <p v-if="request.description" class="mt-3 break-words text-sm text-surface-600 dark:text-surface-300">
            {{ request.description }}
          </p>
          <div v-if="request.status === 'DRAFT' || request.status === 'SENT'" class="mt-4 flex flex-wrap gap-2">
            <Button
              v-if="request.status === 'DRAFT'"
              :data-testid="`send-payment-request-${request.id}`"
              :label="t('payment.membership.paymentRequest.send')"
              icon="pi pi-send"
              :loading="actingId === request.id"
              @click="confirmAction(request, 'send')"
            />
            <Button
              :label="t('payment.membership.paymentRequest.cancel')"
              icon="pi pi-times"
              severity="danger"
              outlined
              :loading="actingId === request.id"
              @click="confirmAction(request, 'cancel')"
            />
          </div>
        </SectionCard>
      </div>
    </template>
  </div>
</template>
