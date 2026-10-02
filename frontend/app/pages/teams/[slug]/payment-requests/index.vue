<script setup lang="ts">
import type { PaymentRequestResponse, PaymentRequestStatus } from '~/types/paymentRequest'

definePageMeta({ middleware: 'auth', layout: 'default' })

const route = useRoute()
const { t, locale } = useI18n()
const { isAdminOrDeputy } = useTeamShellContext()
const { resolveScopeId } = useActivityScopeId()
const paymentRequestApi = usePaymentRequestApi()
const { handleApiError } = useErrorHandler()

const teamSlug = computed(() => String(route.params.slug))
const teamId = ref<number | null>(null)
const requests = ref<PaymentRequestResponse[]>([])
const loading = ref(true)

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
    teamId.value = await resolveScopeId('TEAM', teamSlug.value)
    if (teamId.value === null) return
    const response = await paymentRequestApi.listTeamPaymentRequests(teamId.value)
    requests.value = response.data ?? []
  } catch (error: unknown) {
    handleApiError(error, 'payment-request.team.list')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <PageHeader
      :title="t('payment.membership.paymentRequest.title')"
    />
    <div v-if="isAdminOrDeputy" class="flex justify-end">
      <Button
        data-testid="payment-advances-link"
        :label="t('payment.membership.paymentRequest.advanceTitle')"
        icon="pi pi-wallet"
        severity="secondary"
        outlined
        as="router-link"
        :to="`/teams/${teamSlug}/payments/advances`"
      />
    </div>
    <PageLoading v-if="loading" />
    <Message v-else-if="!isAdminOrDeputy" severity="error" :closable="false">
      {{ t('payment.admin.permissionDenied') }}
    </Message>
    <Message v-else-if="teamId === null" severity="error" :closable="false">
      {{ t('payment.membership.paymentRequest.loadError') }}
    </Message>
    <DashboardEmptyState
      v-else-if="requests.length === 0"
      icon="pi pi-inbox"
      :message="t('payment.membership.paymentRequest.empty')"
    />
    <div v-else class="grid gap-3">
      <NuxtLink
        v-for="request in requests"
        :key="request.id"
        :to="`/teams/${teamSlug}/payment-requests/${request.id}`"
        class="block rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
      >
        <SectionCard :title="request.title || '-'" class="transition hover:border-primary-400">
          <div class="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <dl class="grid min-w-0 grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
              <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.amount') }}</dt>
              <dd class="font-semibold">{{ amountLabel(request) }}</dd>
              <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.due') }}</dt>
              <dd>{{ request.dueDate || '-' }}</dd>
            </dl>
            <Tag
              data-testid="payment-request-status"
              :data-status="request.status"
              :value="statusLabel(request.status)"
              :severity="statusSeverity(request.status)"
            />
          </div>
          <p v-if="request.description" class="mt-3 line-clamp-2 break-words text-sm text-surface-600 dark:text-surface-300">
            {{ request.description }}
          </p>
        </SectionCard>
      </NuxtLink>
    </div>
  </div>
</template>
