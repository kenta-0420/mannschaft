<script setup lang="ts">
import type { MyPaymentResponse, MemberPaymentReceiptResponse } from '~/types/payment'

/**
 * F08.9 P8: 領収書一覧ページ（払い手・受益者向け）。
 * PAID 済みの支払い一覧を取得し、各行に「領収書をダウンロード/表示」ボタンを表示する。
 * BE GET /api/v1/member-payments/{id}/receipt は P8 実装後に実値が返る。
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const api = usePaymentApi()
const notification = useNotification()
const { formatDate } = useDatetime()

const payments = ref<MyPaymentResponse[]>([])
const loading = ref(false)
/** memberPaymentId → MemberPaymentReceiptResponse のキャッシュ */
const receiptCache = ref<Map<number, MemberPaymentReceiptResponse>>(new Map())
const receiptLoading = ref<Set<number>>(new Set())

async function load() {
  loading.value = true
  try {
    const res = await api.getMyPayments()
    // PAID 済みのみ表示（領収書の対象）
    payments.value = res.data.filter((p) => p.statusInfo.status === 'PAID')
  } catch {
    notification.error(t('payment.receipt.loadError'))
  } finally {
    loading.value = false
  }
}

/** 領収書をダウンロード / 表示する。receiptUrl があれば新規タブで開く。 */
async function openReceipt(payment: MyPaymentResponse) {
  const cached = receiptCache.value.get(payment.id)
  if (cached) {
    if (cached.receiptUrl) {
      window.open(cached.receiptUrl, '_blank', 'noopener,noreferrer')
    } else {
      notification.info(t('payment.receipt.pending'))
    }
    return
  }

  receiptLoading.value = new Set([...receiptLoading.value, payment.id])
  try {
    const res = await api.getReceipt(payment.id)
    receiptCache.value = new Map(receiptCache.value).set(payment.id, res.data)
    if (res.data.receiptUrl) {
      window.open(res.data.receiptUrl, '_blank', 'noopener,noreferrer')
    } else {
      notification.info(t('payment.receipt.pending'))
    }
  } catch {
    notification.error(t('payment.receipt.loadReceiptError'))
  } finally {
    const next = new Set(receiptLoading.value)
    next.delete(payment.id)
    receiptLoading.value = next
  }
}

/** 金額を通貨フォーマットで返す。 */
function formatAmount(amount: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <PageHeader :title="$t('payment.receipt.title')" class="mb-4" />

    <div v-if="loading" class="flex justify-center p-8">
      <LoadingBounce />
    </div>

    <template v-else>
      <div
        v-if="payments.length === 0"
        class="rounded border border-dashed p-8 text-center text-surface-400"
      >
        {{ $t('payment.receipt.noReceipts') }}
      </div>

      <div v-else class="flex flex-col gap-3">
        <div
          v-for="payment in payments"
          :key="payment.id"
          class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="flex-1">
              <div class="font-semibold">
                {{ payment.paymentItem.name }}
              </div>
              <div class="mt-1 flex flex-wrap items-center gap-2 text-sm text-surface-500">
                <!-- 金額 -->
                <span>{{ formatAmount(payment.money.amountPaid, payment.money.currency) }}</span>
                <!-- スコープ（所属チーム/組織） -->
                <span aria-hidden="true">·</span>
                <span>{{ payment.scope.name }}</span>
                <!-- 支払い日 -->
                <template v-if="payment.statusInfo.paidAt">
                  <span aria-hidden="true">·</span>
                  <span>{{ formatDate(payment.statusInfo.paidAt) }}</span>
                </template>
              </div>
            </div>

            <!-- 領収書ボタン -->
            <Button
              :label="$t('payment.receipt.download')"
              icon="pi pi-download"
              size="small"
              severity="secondary"
              :loading="receiptLoading.has(payment.id)"
              @click="openReceipt(payment)"
            />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
