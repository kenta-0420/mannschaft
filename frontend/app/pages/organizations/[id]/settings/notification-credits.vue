<script setup lang="ts">
import type { NotificationCreditBalance, NotificationCreditPurchase } from '~/types/notification-credit'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const { formatDate: formatDateBase } = useDatetime()
const route = useRoute()
const toast = useToast()
const orgId = String(route.params.id)

const creditApi = useNotificationCreditApi()

// ─── 状態 ───────────────────────────────────────────────────
const balance = ref<NotificationCreditBalance | null>(null)
const packages = ref<Awaited<ReturnType<typeof creditApi.listPackages>> extends { data: infer T } ? T : never[]>([])
const purchases = ref<NotificationCreditPurchase[]>([])
const purchasingId = ref<number | null>(null)
const loading = ref(false)

// ─── 初期ロード ─────────────────────────────────────────────
async function fetchAll() {
  loading.value = true
  try {
    const [balanceRes, packagesRes, purchasesRes] = await Promise.all([
      creditApi.getBalance(orgId),
      creditApi.listPackages(),
      creditApi.listPurchases(orgId),
    ])
    balance.value = balanceRes.data
    packages.value = packagesRes.data
    purchases.value = purchasesRes.data
  } catch (e) {
    console.error('通知クレジット情報の取得失敗', e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await fetchAll()

  // ?payment=success のクエリパラメータを検知して決済完了トースト表示
  if (route.query.payment === 'success') {
    toast.add({
      severity: 'success',
      summary: t('notificationCredit.purchaseSuccess'),
      life: 3000,
    })
    // クレジット残高を再取得
    await fetchAll()
    // クエリパラメータを除去
    navigateTo(
      { path: route.path, query: {} },
      { replace: true },
    )
  } else if (route.query.payment === 'cancelled') {
    toast.add({
      severity: 'warn',
      summary: t('notificationCredit.purchaseCancelled'),
      life: 3000,
    })
    navigateTo(
      { path: route.path, query: {} },
      { replace: true },
    )
  }
})

// ─── 残高パネル計算値 ────────────────────────────────────────
const freeProgressPercent = computed(() => {
  if (!balance.value) return 0
  return Math.min(100, Math.round((balance.value.freeUsedThisMonth / balance.value.freeQuota) * 100))
})

const progressColor = computed(() => {
  if (freeProgressPercent.value >= 90) return 'red'
  if (freeProgressPercent.value >= 70) return 'orange'
  return 'green'
})

// ─── 購入処理 ────────────────────────────────────────────────
async function handlePurchase(packageId: number) {
  purchasingId.value = packageId
  try {
    const res = await creditApi.createCheckout(orgId, packageId)
    // Stripe Checkout へリダイレクト
    window.location.href = res.data.checkoutUrl
  } catch (e) {
    console.error('Checkout 作成失敗', e)
    toast.add({
      severity: 'error',
      summary: t('notificationCredit.checkoutFailed'),
      life: 3000,
    })
    purchasingId.value = null
  }
}

// ─── 購入ステータス表示用ヘルパー ────────────────────────────
function statusLabel(status: NotificationCreditPurchase['paymentStatus']): string {
  const map: Record<string, string> = {
    PENDING: t('notificationCredit.status.pending'),
    PAID: t('notificationCredit.status.paid'),
    CANCELLED: t('notificationCredit.status.cancelled'),
    REFUNDED: t('notificationCredit.status.refunded'),
  }
  return map[status] ?? status
}

function statusColor(status: NotificationCreditPurchase['paymentStatus']): string {
  const map: Record<string, string> = {
    PENDING: 'yellow',
    PAID: 'green',
    CANCELLED: 'gray',
    REFUNDED: 'orange',
  }
  return map[status] ?? 'gray'
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return formatDateBase(dateStr)
}

function formatNumber(n: number): string {
  return n.toLocaleString('ja-JP')
}
</script>

<template>
  <div class="space-y-8 p-6">
    <PageHeader :title="$t('notificationCredit.title')" />

    <!-- ローディング中 -->
    <div v-if="loading" class="flex justify-center py-12">
      <UIcon name="i-heroicons-arrow-path" class="animate-spin w-8 h-8 text-gray-400" />
    </div>

    <template v-else>
      <!-- ─── セクション1: 残高パネル ─────────────────────────────── -->
      <section class="bg-white dark:bg-gray-900 rounded-xl shadow p-6 space-y-4">
        <h2 class="text-lg font-semibold text-gray-800 dark:text-gray-100">
          {{ $t('notificationCredit.balanceTitle') }}
        </h2>

        <!-- 猶予期間中バナー -->
        <UAlert
          v-if="balance?.inGracePeriod"
          icon="i-heroicons-exclamation-triangle"
          color="red"
          :title="$t('notificationCredit.gracePeriodAlert')"
          :description="$t('notificationCredit.gracePeriodDescription', {
            endsAt: balance.gracePeriodEndsAt ? formatDate(balance.gracePeriodEndsAt) : '—',
            debt: formatNumber(balance.gracePeriodDebt),
          })"
        />

        <!-- 無料枠プログレスバー -->
        <div class="space-y-2">
          <div class="flex justify-between text-sm text-gray-600 dark:text-gray-400">
            <span>{{ $t('notificationCredit.freeUsage') }}</span>
            <span>
              {{ formatNumber(balance?.freeUsedThisMonth ?? 0) }}
              / {{ formatNumber(balance?.freeQuota ?? 10000) }} {{ $t('notificationCredit.unit') }}
            </span>
          </div>
          <UProgress
            :value="freeProgressPercent"
            :color="progressColor"
            size="md"
          />
        </div>

        <!-- クレジット残高 -->
        <div class="flex items-center gap-3 pt-2">
          <UIcon name="i-heroicons-credit-card" class="w-5 h-5 text-primary-500" />
          <div>
            <p class="text-sm text-gray-500 dark:text-gray-400">{{ $t('notificationCredit.creditBalance') }}</p>
            <p
              class="text-2xl font-bold"
              :class="(balance?.creditBalance ?? 0) < 0 ? 'text-red-600' : 'text-gray-900 dark:text-gray-100'"
            >
              {{ formatNumber(balance?.creditBalance ?? 0) }} {{ $t('notificationCredit.unit') }}
            </p>
          </div>
        </div>
      </section>

      <!-- ─── セクション2: パッケージ購入カード ────────────────────── -->
      <section class="space-y-4">
        <h2 class="text-lg font-semibold text-gray-800 dark:text-gray-100">
          {{ $t('notificationCredit.packageTitle') }}
        </h2>

        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div
            v-for="pkg in packages"
            :key="pkg.id"
            class="bg-white dark:bg-gray-900 rounded-xl shadow p-5 space-y-3 flex flex-col"
          >
            <h3 class="font-semibold text-gray-800 dark:text-gray-100">{{ pkg.name }}</h3>
            <div class="space-y-1">
              <p class="text-sm text-gray-500">{{ $t('notificationCredit.credits') }}</p>
              <p class="text-xl font-bold text-primary-600">
                {{ formatNumber(pkg.credits) }} {{ $t('notificationCredit.unit') }}
              </p>
            </div>
            <div class="space-y-1">
              <p class="text-sm text-gray-500">{{ $t('notificationCredit.price') }}</p>
              <p class="text-lg font-semibold text-gray-800 dark:text-gray-100">
                ¥{{ formatNumber(pkg.priceJpy) }}
              </p>
            </div>
            <div class="mt-auto pt-2">
              <UButton
                block
                color="primary"
                :loading="purchasingId === pkg.id"
                :disabled="purchasingId !== null"
                @click="handlePurchase(pkg.id)"
              >
                {{ $t('notificationCredit.purchaseButton') }}
              </UButton>
            </div>
          </div>
        </div>
      </section>

      <!-- ─── セクション3: 購入履歴テーブル ───────────────────────── -->
      <section class="bg-white dark:bg-gray-900 rounded-xl shadow p-6 space-y-4">
        <h2 class="text-lg font-semibold text-gray-800 dark:text-gray-100">
          {{ $t('notificationCredit.historyTitle') }}
        </h2>

        <div v-if="purchases.length === 0" class="py-8 text-center text-gray-400">
          {{ $t('notificationCredit.noHistory') }}
        </div>

        <UTable
          v-else
          :rows="purchases"
          :columns="[
            { key: 'paidAt', label: $t('notificationCredit.table.paidAt') },
            { key: 'packageName', label: $t('notificationCredit.table.packageName') },
            { key: 'priceJpy', label: $t('notificationCredit.table.priceJpy') },
            { key: 'creditsGranted', label: $t('notificationCredit.table.credits') },
            { key: 'expiresAt', label: $t('notificationCredit.table.expiresAt') },
            { key: 'paymentStatus', label: $t('notificationCredit.table.status') },
          ]"
        >
          <template #paidAt-data="{ row }">
            {{ formatDate(row.paidAt) }}
          </template>
          <template #priceJpy-data="{ row }">
            ¥{{ formatNumber(row.priceJpy) }}
          </template>
          <template #creditsGranted-data="{ row }">
            {{ formatNumber(row.creditsGranted) }} {{ $t('notificationCredit.unit') }}
          </template>
          <template #expiresAt-data="{ row }">
            {{ formatDate(row.expiresAt) }}
          </template>
          <template #paymentStatus-data="{ row }">
            <UBadge :color="statusColor(row.paymentStatus)" size="sm">
              {{ statusLabel(row.paymentStatus) }}
            </UBadge>
          </template>
        </UTable>
      </section>
    </template>
  </div>
</template>
