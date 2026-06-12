<script setup lang="ts">
import type { components } from '~/types/generated/index'

type TournamentFeeResponse = components['schemas']['TournamentFeeResponse']
type CheckoutResponse = components['schemas']['CheckoutResponse']

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const tId = Number(route.params.tId)
// orgId はクエリパラメータ経由で受け取る（大会は組織に属するため）
const orgId = String(route.query.orgId ?? '')

const { listFees, checkout } = useTournamentFee(orgId, tId)
const notification = useNotification()

// ===== 一覧 =====
const fees = ref<TournamentFeeResponse[]>([])
const loading = ref(true)

async function loadFees() {
  loading.value = true
  try {
    fees.value = await listFees()
  } catch {
    notification.error(t('tournament.fees.error_load'))
  } finally {
    loading.value = false
  }
}

// ===== 支払い =====
const checkingOutFeeId = ref<string | null>(null)

async function handleCheckout(fee: TournamentFeeResponse) {
  if (!fee.id) return
  checkingOutFeeId.value = fee.id
  try {
    const result: CheckoutResponse = await checkout(fee.id, teamSlug)
    if (result.checkoutUrl) {
      // Stripe Checkout リダイレクト
      notification.info(t('tournament.fees.checkout_redirect'))
      window.location.href = result.checkoutUrl
    } else {
      // MANUAL 支払い（URLなし）
      notification.success(t('tournament.fees.checkout_manual_info'))
      await loadFees()
    }
  } catch {
    notification.error(t('tournament.fees.error_checkout'))
  } finally {
    checkingOutFeeId.value = null
  }
}

// ===== ヘルパー =====
function formatAmount(fee: TournamentFeeResponse): string {
  if (fee.amount == null) return '-'
  const currency = fee.currency ?? 'JPY'
  return new Intl.NumberFormat('ja-JP', { style: 'currency', currency }).format(fee.amount)
}

function formatDueDate(fee: TournamentFeeResponse): string {
  if (!fee.paymentDue) return '-'
  return new Date(fee.paymentDue).toLocaleDateString('ja-JP')
}

function isOverdue(fee: TournamentFeeResponse): boolean {
  if (!fee.paymentDue) return false
  return new Date(fee.paymentDue) < new Date()
}

onMounted(() => {
  if (!orgId) {
    notification.error('組織IDが指定されていません')
    return
  }
  loadFees()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton :to="`/teams/${teamSlug}/tournaments`" :label="$t('tournament.fees.title')" />
    </div>

    <div class="mb-6">
      <PageHeader :title="$t('tournament.fees.title')" />
    </div>

    <PageLoading v-if="loading" size="40px" />
    <template v-else>
      <DashboardEmptyState
        v-if="fees.length === 0"
        icon="pi pi-money-bill"
        :message="$t('tournament.fees.empty')"
      />
      <div v-else class="grid gap-4 sm:grid-cols-2">
        <SectionCard
          v-for="fee in fees"
          :key="fee.id"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0 flex-1">
              <h3 class="truncate text-sm font-semibold">{{ fee.title || $t('tournament.fees.title') }}</h3>
              <p class="mt-1 text-xl font-bold text-primary-600">{{ formatAmount(fee) }}</p>
              <div class="mt-2 flex flex-wrap gap-2 text-xs text-surface-500">
                <span>{{ $t('tournament.fees.due_date') }}: {{ formatDueDate(fee) }}</span>
                <span
                  v-if="isOverdue(fee)"
                  class="rounded bg-red-100 px-1.5 py-0.5 text-red-600"
                >
                  期限超過
                </span>
              </div>
            </div>
            <div class="shrink-0">
              <Button
                :label="$t('tournament.fees.pay')"
                icon="pi pi-credit-card"
                size="small"
                :loading="checkingOutFeeId === fee.id"
                @click="handleCheckout(fee)"
              />
            </div>
          </div>
        </SectionCard>
      </div>
    </template>
  </div>
</template>
