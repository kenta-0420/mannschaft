<script setup lang="ts">
import type { PaymentItemResponse, MemberPaymentResponse, PaymentItemType } from '~/types/payment'

const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: string }>()

const { t } = useI18n()
const { getPaymentItems, getMemberPayments, sendReminder } = usePaymentApi()
const { showSuccess, showError } = useNotification()

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

async function loadItems() {
  try {
    const res = await getPaymentItems(props.scopeType, props.scopeId)
    items.value = res.data
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

function getStatusClass(s: string): string {
  switch (s) { case 'PAID': return 'bg-green-100 text-green-700'; case 'PENDING': case 'UNPAID': return 'bg-yellow-100 text-yellow-700'; case 'REFUNDED': return 'bg-blue-100 text-blue-700'; default: return 'bg-surface-100 text-surface-500' }
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
        </div>
        <Button :label="t('payment.admin.remindUnpaid')" icon="pi pi-bell" text size="small" @click="onRemind" />
      </div>
      <div v-if="loading" class="flex justify-center py-8"><LoadingBounce /></div>
      <div v-else-if="selectedItem" class="flex flex-col gap-1">
        <div v-for="p in payments" :key="p.id" class="flex items-center gap-3 rounded-lg border border-surface-100 px-4 py-2">
          <Avatar :label="p.userName?.charAt(0)" shape="circle" size="small" />
          <span class="flex-1 text-sm">{{ p.userName }}</span>
          <span :class="getStatusClass(p.statusInfo.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{ p.statusInfo.status }}</span>
          <span v-if="p.statusInfo.paidAt" class="text-xs text-surface-400">{{ p.statusInfo.paidAt }}</span>
        </div>
      </div>
      <div v-else class="py-12 text-center text-surface-400">{{ $t('payment.admin.selectItem') }}</div>
    </div>
  </div>
</template>
