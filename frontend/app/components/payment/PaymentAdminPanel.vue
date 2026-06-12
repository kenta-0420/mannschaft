<script setup lang="ts">
import type { PaymentItemResponse, MemberPaymentResponse, PaymentItemType, PaymentSummaryResponse } from '~/types/payment'

const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: string }>()

const { t } = useI18n()
const { getPaymentItems, getMemberPayments, sendReminder, getPaymentSummary, exportPayments } = usePaymentApi()
const { showSuccess, showError } = useNotification()

/** F08.9 P8: CSV ダウンロード中フラグ */
const csvDownloading = ref(false)

/**
 * 支払い種別バッジのスタイルクラスを返す。
 */
function getTypeClass(type: PaymentItemType): string {
  switch (type) {
    case 'ANNUAL_FEE': return 'bg-blue-100 text-blue-700'
    case 'MONTHLY_FEE': return 'bg-indigo-100 text-indigo-700'
    case 'ITEM': return 'bg-surface-100 text-surface-600'
    case 'DONATION': return 'bg-green-100 text-green-700'
    case 'TERM': return 'bg-orange-100 text-orange-700'
    default: return 'bg-surface-100 text-surface-500'
  }
}

/**
 * 支払い種別ラベルを i18n キーから取得する。
 */
function getTypeLabel(type: PaymentItemType): string {
  const keyMap: Record<PaymentItemType, string> = {
    ANNUAL_FEE: t('payment.term.typeLabel.ANNUAL_FEE'),
    MONTHLY_FEE: t('payment.term.typeLabel.MONTHLY_FEE'),
    ITEM: t('payment.term.typeLabel.ITEM'),
    DONATION: t('payment.term.typeLabel.DONATION'),
    TERM: t('payment.term.typeLabel.TERM'),
  }
  return keyMap[type] ?? type
}

const items = ref<PaymentItemResponse[]>([])
const selectedItem = ref<PaymentItemResponse | null>(null)
const payments = ref<MemberPaymentResponse[]>([])
const loading = ref(false)
/** F08.9 P8: サマリー（支払い項目ごとの PAID/UNPAID/EXPIRED 件数）。 */
const summary = ref<PaymentSummaryResponse | null>(null)

/** 選択中の支払い項目のサマリー行。 */
const selectedSummaryItem = computed(() =>
  summary.value?.items.find((s) => s.paymentItemId === selectedItem.value?.id) ?? null,
)

async function loadItems() {
  try {
    const [itemsRes, summaryRes] = await Promise.all([
      getPaymentItems(props.scopeType, props.scopeId),
      getPaymentSummary(props.scopeType, props.scopeId).catch(() => null),
    ])
    items.value = itemsRes.data
    summary.value = summaryRes?.data ?? null
  } catch { showError(t('payment.admin.loadItemsError')) }
}

async function loadPayments(item: PaymentItemResponse) {
  selectedItem.value = item
  loading.value = true
  try {
    const res = await getMemberPayments(props.scopeType, props.scopeId, item.id)
    payments.value = res.data
  } catch { showError(t('payment.admin.loadPaymentsError')) }
  finally { loading.value = false }
}

async function onRemind() {
  if (!selectedItem.value) return
  try {
    await sendReminder(props.scopeType, props.scopeId, selectedItem.value.id)
    showSuccess(t('payment.admin.remindSuccess'))
  } catch { showError(t('payment.admin.remindError')) }
}

/**
 * F08.9 P8: 支払い一覧を CSV としてダウンロードする。
 * BE: GET /api/v1/teams/{id}/payment-items/{itemId}/payments/export
 */
async function onExportCsv() {
  if (!selectedItem.value) return
  csvDownloading.value = true
  try {
    const blob = await exportPayments(props.scopeType, props.scopeId, selectedItem.value.id)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `payments_${selectedItem.value.id}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch { showError(t('payment.admin.exportError')) }
  finally { csvDownloading.value = false }
}

/**
 * F08.9 P8: 支払い状態の PrimeVue Tag severity。
 * EXPIRED = 期限切れ（赤）, PAID = 支払い済み（緑）, UNPAID/PENDING = 未払い（橙）, その他（グレー）。
 */
function statusSeverity(status: string): 'success' | 'warn' | 'danger' | 'secondary' | 'info' {
  switch (status) {
    case 'PAID': return 'success'
    case 'UNPAID': case 'PENDING': return 'warn'
    case 'EXPIRED': return 'danger'
    default: return 'secondary'
  }
}

function statusLabel(status: string): string {
  const key = `payment.admin.status.${status}`
  return t(key, status)
}

onMounted(() => loadItems())
</script>

<template>
  <div class="flex gap-4">
    <!-- 項目一覧 -->
    <div class="w-64 shrink-0 rounded-xl border border-surface-300 bg-surface-0 p-3">
      <h3 class="mb-3 text-sm font-semibold">{{ $t('payment.admin.itemsTitle') }}</h3>
      <button v-for="item in items" :key="item.id" class="mb-1 w-full rounded-lg px-3 py-2 text-left text-sm transition-colors hover:bg-surface-100" :class="selectedItem?.id === item.id ? 'bg-primary/10 text-primary' : ''" @click="loadPayments(item)">
        <div class="flex items-center gap-1.5">
          <span class="font-medium">{{ item.meta.name }}</span>
          <span :class="getTypeClass(item.meta.type)" class="rounded px-1.5 py-0.5 text-xs font-medium">
            {{ getTypeLabel(item.meta.type) }}
          </span>
        </div>
        <div class="text-xs text-surface-400">¥{{ item.money.amount.toLocaleString() }}</div>
        <!-- F08.9 P6: TERM 型の有効期間表示 -->
        <div v-if="item.meta.type === 'TERM' && item.term" class="mt-0.5 flex items-center gap-1 text-xs text-orange-600">
          <i class="pi pi-calendar" aria-hidden="true" />
          <span v-if="item.term.termStartsOn && item.term.termEndsOn">
            {{ item.term.termStartsOn }} 〜 {{ item.term.termEndsOn }}
          </span>
          <span v-else-if="item.term.termStartsOn">{{ item.term.termStartsOn }} 〜</span>
          <span v-else-if="item.term.termEndsOn">〜 {{ item.term.termEndsOn }}</span>
        </div>
      </button>
    </div>

    <!-- 支払い状況 -->
    <div class="flex-1">
      <div v-if="selectedItem" class="mb-4 flex items-start justify-between gap-2">
        <div>
          <h3 class="text-lg font-semibold">{{ selectedItem.meta.name }}</h3>
          <!-- F08.9 P6: TERM 型の有効期間表示 -->
          <div v-if="selectedItem.meta.type === 'TERM' && selectedItem.term" class="mt-1 flex items-center gap-1 text-sm text-orange-600">
            <i class="pi pi-calendar" aria-hidden="true" />
            <span class="font-medium">{{ t('payment.term.periodLabel') }}:</span>
            <span v-if="selectedItem.term.termStartsOn && selectedItem.term.termEndsOn">
              {{ selectedItem.term.termStartsOn }} 〜 {{ selectedItem.term.termEndsOn }}
            </span>
            <span v-else-if="selectedItem.term.termStartsOn">{{ selectedItem.term.termStartsOn }} 〜</span>
            <span v-else-if="selectedItem.term.termEndsOn">〜 {{ selectedItem.term.termEndsOn }}</span>
          </div>
          <!-- F08.9 P8: 支払い状態3区分サマリー -->
          <div v-if="selectedSummaryItem" class="mt-2 flex flex-wrap items-center gap-2">
            <Tag
              :value="`${t('payment.admin.status.PAID')} ${selectedSummaryItem.paidCount}`"
              severity="success"
              rounded
            />
            <Tag
              :value="`${t('payment.admin.status.UNPAID')} ${selectedSummaryItem.unpaidCount}`"
              severity="warn"
              rounded
            />
            <Tag
              :value="`${t('payment.admin.status.EXPIRED')} ${selectedSummaryItem.expiredCount}`"
              severity="danger"
              rounded
            />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Button :label="t('payment.exportCsv')" icon="pi pi-download" text size="small" :loading="csvDownloading" @click="onExportCsv" />
          <Button :label="t('payment.admin.remindUnpaid')" icon="pi pi-bell" text size="small" @click="onRemind" />
        </div>
      </div>
      <div v-if="loading" class="flex justify-center py-8"><LoadingBounce /></div>
      <div v-else-if="selectedItem" class="flex flex-col gap-1">
        <div v-for="p in payments" :key="p.id" class="flex items-center gap-3 rounded-lg border border-surface-100 px-4 py-2">
          <Avatar :label="p.userName?.charAt(0)" shape="circle" size="small" />
          <span class="flex-1 text-sm">{{ p.userName }}</span>
          <!-- F08.9 P8: PrimeVue Tag で状態を色分け表示（PAID=緑・UNPAID/PENDING=橙・EXPIRED=赤） -->
          <Tag
            :value="statusLabel(p.statusInfo.status)"
            :severity="statusSeverity(p.statusInfo.status)"
            rounded
          />
          <span v-if="p.statusInfo.paidAt" class="text-xs text-surface-400">{{ p.statusInfo.paidAt }}</span>
        </div>
      </div>
      <div v-else class="py-12 text-center text-surface-400">{{ $t('payment.admin.selectItem') }}</div>
    </div>
  </div>
</template>
