<script setup lang="ts">
import type { FeeStatementResponse } from '~/types/payment'

/**
 * F08.9 P8: チーム月次手数料明細ページ。
 * チーム ADMIN のみアクセス可。
 * BE: GET /api/v1/teams/{id}/fee-statements?period=YYYY-MM
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = String(route.params.slug)
const { getFeeStatement } = usePaymentApi()
const notification = useNotification()

/** 選択中の対象月（YYYY-MM 形式） */
const selectedPeriod = ref<string>(currentYearMonth())
const statement = ref<FeeStatementResponse | null>(null)
const loading = ref(false)
const noData = ref(false)

/** 現在の年月を YYYY-MM 形式で返す。 */
function currentYearMonth(): string {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

/** 手数料金額を通貨フォーマットで返す。 */
function formatAmount(amount: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
}

async function load() {
  loading.value = true
  noData.value = false
  statement.value = null
  try {
    const res = await getFeeStatement(teamId, selectedPeriod.value)
    statement.value = res.data
  } catch (err: unknown) {
    // 404 = 対象月のデータなし、それ以外はエラー通知
    const status = (err as { status?: number })?.status
    if (status === 404) {
      noData.value = true
    } else {
      notification.error(t('payment.feeStatements.loadError'))
    }
  } finally {
    loading.value = false
  }
}

watch(selectedPeriod, () => load())
onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-2xl p-4">
    <PageHeader :title="$t('payment.feeStatements.title')" class="mb-4" />

    <!-- 月選択 -->
    <div class="mb-6 flex items-center gap-3">
      <label class="text-sm font-medium text-surface-600" for="fee-period">
        {{ $t('payment.feeStatements.period') }}
      </label>
      <input
        id="fee-period"
        v-model="selectedPeriod"
        type="month"
        class="rounded-lg border border-surface-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 dark:border-surface-600 dark:bg-surface-800"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-12">
      <LoadingBounce />
    </div>

    <!-- データなし -->
    <div
      v-else-if="noData"
      class="rounded-lg border border-dashed border-surface-300 p-10 text-center text-surface-400"
    >
      {{ $t('payment.feeStatements.noData') }}
    </div>

    <!-- 明細表示 -->
    <div
      v-else-if="statement"
      class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-900"
    >
      <dl class="flex flex-col gap-4">
        <!-- 対象月 -->
        <div class="flex items-center justify-between border-b border-surface-100 pb-3 dark:border-surface-700">
          <dt class="text-sm font-medium text-surface-500">
            {{ $t('payment.feeStatements.period') }}
          </dt>
          <dd class="font-semibold">{{ statement.period }}</dd>
        </div>

        <!-- 手数料合計 -->
        <div class="flex items-center justify-between border-b border-surface-100 pb-3 dark:border-surface-700">
          <dt class="text-sm font-medium text-surface-500">
            {{ $t('payment.feeStatements.totalFeeAmount') }}
          </dt>
          <dd class="text-xl font-bold text-primary">
            {{ formatAmount(statement.totalFeeAmount, statement.currency) }}
          </dd>
        </div>

        <!-- 発行者 -->
        <div class="flex items-center justify-between">
          <dt class="text-sm font-medium text-surface-500">
            {{ $t('payment.feeStatements.issuerName') }}
          </dt>
          <dd class="text-sm">{{ statement.issuerName }}</dd>
        </div>
      </dl>
    </div>
  </div>
</template>
