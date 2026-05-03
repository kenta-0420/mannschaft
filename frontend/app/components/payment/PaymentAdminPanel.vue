<script setup lang="ts">
import type { PaymentItemResponse, MemberPaymentResponse } from '~/types/payment'

const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: number }>()

const { getPaymentItems, getMemberPayments, sendReminder } = usePaymentApi()
const { showSuccess, showError } = useNotification()

const items = ref<PaymentItemResponse[]>([])
const selectedItem = ref<PaymentItemResponse | null>(null)
const payments = ref<MemberPaymentResponse[]>([])
const loading = ref(false)

async function loadItems() {
  try {
    const res = await getPaymentItems(props.scopeType, props.scopeId)
    items.value = res.data
  } catch { showError('支払い項目の取得に失敗しました') }
}

async function loadPayments(item: PaymentItemResponse) {
  selectedItem.value = item
  loading.value = true
  try {
    const res = await getMemberPayments(props.scopeType, props.scopeId, item.id)
    payments.value = res.data
  } catch { showError('支払い状況の取得に失敗しました') }
  finally { loading.value = false }
}

async function onRemind() {
  if (!selectedItem.value) return
  try {
    await sendReminder(props.scopeType, props.scopeId, selectedItem.value.id)
    showSuccess('リマインドを送信しました')
  } catch { showError('送信に失敗しました') }
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
      <h3 class="mb-3 text-sm font-semibold">支払い項目</h3>
      <button v-for="item in items" :key="item.id" class="mb-1 w-full rounded-lg px-3 py-2 text-left text-sm transition-colors hover:bg-surface-100" :class="selectedItem?.id === item.id ? 'bg-primary/10 text-primary' : ''" @click="loadPayments(item)">
        <div class="font-medium">{{ item.name }}</div>
        <div class="text-xs text-surface-400">¥{{ item.amount.toLocaleString() }}</div>
      </button>
    </div>

    <!-- 支払い状況 -->
    <div class="flex-1">
      <div v-if="selectedItem" class="mb-4 flex items-center justify-between">
        <h3 class="text-lg font-semibold">{{ selectedItem.name }}</h3>
        <Button label="未払いリマインド" icon="pi pi-bell" text size="small" @click="onRemind" />
      </div>
      <div v-if="loading" class="flex justify-center py-8"><ProgressSpinner style="width: 40px; height: 40px" /></div>
      <div v-else-if="selectedItem" class="flex flex-col gap-1">
        <div v-for="p in payments" :key="p.id" class="flex items-center gap-3 rounded-lg border border-surface-100 px-4 py-2">
          <Avatar :label="p.displayName.charAt(0)" shape="circle" size="small" />
          <span class="flex-1 text-sm">{{ p.displayName }}</span>
          <span :class="getStatusClass(p.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{ p.status }}</span>
          <span v-if="p.paidAt" class="text-xs text-surface-400">{{ p.paidAt }}</span>
        </div>
      </div>
      <div v-else class="py-12 text-center text-surface-400">支払い項目を選択してください</div>
    </div>
  </div>
</template>
