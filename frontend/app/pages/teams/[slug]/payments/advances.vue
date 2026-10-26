<script setup lang="ts">
import type { TeamPaymentAdvanceResponse } from '~/types/paymentRequest'

definePageMeta({ middleware: 'auth', layout: 'default' })

const route = useRoute()
const { t, locale } = useI18n()
const paymentRequestApi = usePaymentRequestApi()
const notification = useNotification()
const confirm = useConfirm()

const teamSlug = computed(() => String(route.params.slug))
const { data: advances, teamId, loading, state, loadError, load, fail } = useTeamPaymentPage(
  teamSlug,
  async (id) => (await paymentRequestApi.listTeamPaymentAdvances(id)).data ?? [],
  () => [] as TeamPaymentAdvanceResponse[],
)
const confirmingId = ref<string | null>(null)

function amountLabel(advance: TeamPaymentAdvanceResponse): string {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency', currency: advance.currency ?? 'JPY',
  }).format(advance.advancedAmount ?? 0)
}


async function settle(advance: TeamPaymentAdvanceResponse): Promise<void> {
  if (!teamId.value || !advance.id || advance.settlementStatus !== 'PENDING' || confirmingId.value) return
  const slug = teamSlug.value
  confirmingId.value = advance.id
  try {
    await paymentRequestApi.confirmPaymentAdvanceSettlement(teamId.value, advance.id)
    if (slug !== teamSlug.value) return
    notification.success(t('payment.membership.paymentRequest.confirmSettlementSuccess'))
    await load()
  } catch (error: unknown) {
    if (slug !== teamSlug.value) return
    fail(error)
  } finally {
    if (slug === teamSlug.value) confirmingId.value = null
  }
}

function confirmSettlement(advance: TeamPaymentAdvanceResponse): void {
  if (advance.settlementStatus !== 'PENDING') return
  const slug = teamSlug.value
  confirm.require({
    message: t('payment.membership.paymentRequest.confirm'),
    header: t('payment.membership.paymentRequest.confirmSettlement'),
    acceptLabel: t('button.confirm'),
    rejectLabel: t('button.cancel'),
    accept: () => { if (slug === teamSlug.value) void settle(advance) },
  })
}

watch(teamSlug, () => { confirmingId.value = null }, { flush: 'sync' })
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <ConfirmDialog />
    <PageHeader
      :title="t('payment.membership.paymentRequest.advanceTitle')"
    />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="state === 'error'" :error="loadError" :show-retry="true" @retry="load" />
    <Message v-else-if="state === 'denied'" severity="error" :closable="false">{{ t('payment.admin.permissionDenied') }}</Message>
    <DashboardEmptyState
      v-else-if="advances.length === 0"
      icon="pi pi-wallet"
      :message="t('payment.membership.paymentRequest.empty')"
    />
    <div v-else class="grid gap-3">
      <SectionCard
        v-for="advance in advances"
        :key="advance.id"
        :title="t('payment.membership.paymentRequest.advanceTitle')"
      >
        <div class="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <dl class="grid min-w-0 grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt class="text-surface-500">{{ t('payment.membership.paymentRequest.amount') }}</dt>
            <dd class="font-semibold">{{ amountLabel(advance) }}</dd>
            <dt class="text-surface-500">{{ t('payment.summary.payer') }}</dt>
            <dd>{{ advance.payerUserId || '-' }}</dd>
          </dl>
          <Tag
            data-testid="payment-advance-status"
            :data-status="advance.settlementStatus"
            :value="t(advance.settlementStatus === 'SETTLED' ? 'payment.membership.paymentRequest.advanceStatusSettled' : 'payment.membership.paymentRequest.advanceStatusPending')"
            :severity="advance.settlementStatus === 'SETTLED' ? 'success' : 'warn'"
          />
        </div>
        <div v-if="advance.settlementStatus === 'PENDING'" class="mt-4 flex justify-end">
          <Button
            :label="t('payment.membership.paymentRequest.confirmSettlement')"
            icon="pi pi-check"
            :loading="confirmingId === advance.id"
            :disabled="Boolean(confirmingId)"
            data-testid="confirm-advance"
            @click="confirmSettlement(advance)"
          />
        </div>
      </SectionCard>
    </div>
  </div>
</template>
