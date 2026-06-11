<script setup lang="ts">
import type { PayableDueItem, BulkCheckoutResultItem } from '~/types/payment'

/**
 * F08.9 P2: 後見まとめ払いページ。
 *
 * 保護者が管理する子ども（および自分自身）の未払い会費を一覧表示し、
 * まとめてチェックアウトできる。
 *
 * フロー:
 *   ①会費一覧取得（GET /api/v1/me/payable-dues）
 *   ②未払い項目をチェックボックスで選択
 *   ③「まとめて支払う」ボタン → 受益者ごとにバルクチェックアウト
 *   ④結果表示（CHECKED_OUT / SKIPPED）
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const payableDuesApi = usePayableDuesApi()
const notification = useNotification()

// ── 状態 ──────────────────────────────────────────────────────

/** 会費一覧 */
const items = ref<PayableDueItem[]>([])
const loading = ref(false)
const loadError = ref(false)

/** 選択済みの paymentItemId セット（beneficiaryUserId ごとに管理） */
type SelectionKey = `${number}:${number}` // `beneficiaryUserId:paymentItemId`
const selected = ref<Set<SelectionKey>>(new Set())

/** バルクチェックアウト状態 */
const processing = ref(false)
const results = ref<BulkCheckoutResultItem[]>([])
const showResult = ref(false)

// ── データ取得 ─────────────────────────────────────────────────

async function loadItems() {
  loading.value = true
  loadError.value = false
  try {
    const res = await payableDuesApi.getPayableDues()
    items.value = res.data.items
  } catch {
    loadError.value = true
    notification.error(t('common.loadError'))
  } finally {
    loading.value = false
  }
}

// ── 受益者別グループ ──────────────────────────────────────────

/** 受益者ごとにグループ化した未払い会費 */
const groupedItems = computed<Map<number, { displayName: string | null; items: PayableDueItem[] }>>(
  () => {
    const map = new Map<number, { displayName: string | null; items: PayableDueItem[] }>()
    for (const item of items.value) {
      const group = map.get(item.beneficiaryUserId)
      if (group) {
        group.items.push(item)
      } else {
        map.set(item.beneficiaryUserId, {
          displayName: item.beneficiaryDisplayName,
          items: [item],
        })
      }
    }
    return map
  },
)

/** 全受益者 ID の配列（表示順安定のため） */
const beneficiaryIds = computed(() => Array.from(groupedItems.value.keys()))

// ── 選択ロジック ───────────────────────────────────────────────

function makeKey(beneficiaryUserId: number, paymentItemId: number): SelectionKey {
  return `${beneficiaryUserId}:${paymentItemId}`
}

function isSelected(beneficiaryUserId: number, paymentItemId: number): boolean {
  return selected.value.has(makeKey(beneficiaryUserId, paymentItemId))
}

function toggleItem(beneficiaryUserId: number, paymentItemId: number, alreadyPaid: boolean) {
  if (alreadyPaid) return
  const key = makeKey(beneficiaryUserId, paymentItemId)
  const next = new Set(selected.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  selected.value = next
}

/** 指定受益者の全未払い項目を選択/解除 */
function toggleAllForBeneficiary(beneficiaryUserId: number) {
  const group = groupedItems.value.get(beneficiaryUserId)
  if (!group) return
  const payableItems = group.items.filter((i) => !i.alreadyPaid)
  const allSelected = payableItems.every((i) => isSelected(beneficiaryUserId, i.paymentItemId))
  const next = new Set(selected.value)
  for (const item of payableItems) {
    const key = makeKey(beneficiaryUserId, item.paymentItemId)
    if (allSelected) {
      next.delete(key)
    } else {
      next.add(key)
    }
  }
  selected.value = next
}

function isAllSelectedForBeneficiary(beneficiaryUserId: number): boolean {
  const group = groupedItems.value.get(beneficiaryUserId)
  if (!group) return false
  const payableItems = group.items.filter((i) => !i.alreadyPaid)
  return payableItems.length > 0 && payableItems.every((i) => isSelected(beneficiaryUserId, i.paymentItemId))
}

// ── 集計 ───────────────────────────────────────────────────────

/** 選択件数 */
const selectedCount = computed(() => selected.value.size)

/** 選択合計金額 */
const totalAmount = computed(() => {
  let total = 0
  for (const key of selected.value) {
    const [beneficiaryUserIdStr, paymentItemIdStr] = key.split(':')
    const beneficiaryUserId = Number(beneficiaryUserIdStr)
    const paymentItemId = Number(paymentItemIdStr)
    const group = groupedItems.value.get(beneficiaryUserId)
    if (!group) continue
    const item = group.items.find((i) => i.paymentItemId === paymentItemId)
    if (item) total += item.totalCharge
  }
  return total
})

// ── チェックアウト ────────────────────────────────────────────

async function handleBulkCheckout() {
  if (selectedCount.value === 0) return
  processing.value = true
  results.value = []

  try {
    // 受益者ごとにグループ化してリクエスト送信
    const grouped = new Map<number, number[]>()
    for (const key of selected.value) {
      const [beneficiaryUserIdStr, paymentItemIdStr] = key.split(':')
      const beneficiaryUserId = Number(beneficiaryUserIdStr)
      const paymentItemId = Number(paymentItemIdStr)
      const arr = grouped.get(beneficiaryUserId) ?? []
      arr.push(paymentItemId)
      grouped.set(beneficiaryUserId, arr)
    }

    const allResults: BulkCheckoutResultItem[] = []
    for (const [beneficiaryUserId, paymentItemIds] of grouped) {
      const res = await payableDuesApi.bulkCheckout({ beneficiaryUserId, paymentItemIds })
      allResults.push(...res.data.results)
    }

    results.value = allResults
    showResult.value = true
    // 完了後に一覧をリロードして最新状態を反映
    selected.value = new Set()
    await loadItems()
  } catch {
    notification.error(t('common.loadError'))
  } finally {
    processing.value = false
  }
}

// ── 結果表示ユーティリティ ────────────────────────────────────

function getItemName(paymentItemId: number): string {
  for (const group of groupedItems.value.values()) {
    const found = group.items.find((i) => i.paymentItemId === paymentItemId)
    if (found) return found.itemName
  }
  return String(paymentItemId)
}

function getSkipReasonLabel(reason: BulkCheckoutResultItem['skipReason']): string {
  if (!reason) return ''
  return t(`payment.guardianBulkPayment.result.skipReasons.${reason}`)
}

// ── 初期化 ────────────────────────────────────────────────────

onMounted(loadItems)
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-2">{{ $t('payment.guardianBulkPayment.title') }}</h1>
    <p class="text-surface-500 text-sm mb-6">{{ $t('payment.guardianBulkPayment.subtitle') }}</p>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-12">
      <ProgressSpinner />
    </div>

    <!-- エラー -->
    <div
      v-else-if="loadError"
      class="rounded-lg bg-red-50 border border-red-200 px-6 py-6 text-center text-red-600"
    >
      {{ $t('common.loadError') }}
    </div>

    <!-- 未払いなし -->
    <div
      v-else-if="items.length === 0"
      class="rounded-lg bg-surface-50 border border-surface-200 px-6 py-10 text-center text-surface-500"
    >
      {{ $t('payment.guardianBulkPayment.noItems') }}
    </div>

    <template v-else>
      <!-- 受益者ごとのグループ -->
      <div
        v-for="beneficiaryUserId in beneficiaryIds"
        :key="beneficiaryUserId"
        class="mb-6"
      >
        <div class="flex items-center justify-between mb-2">
          <h2 class="font-semibold text-surface-700">
            {{ $t('payment.guardianBulkPayment.beneficiary') }}:
            {{ groupedItems.get(beneficiaryUserId)?.displayName ?? `ID: ${beneficiaryUserId}` }}
          </h2>
          <Button
            :label="isAllSelectedForBeneficiary(beneficiaryUserId)
              ? $t('payment.guardianBulkPayment.deselectAll')
              : $t('payment.guardianBulkPayment.selectAll')"
            severity="secondary"
            text
            size="small"
            @click="toggleAllForBeneficiary(beneficiaryUserId)"
          />
        </div>

        <div class="flex flex-col gap-2">
          <div
            v-for="item in groupedItems.get(beneficiaryUserId)?.items ?? []"
            :key="item.paymentItemId"
            class="flex items-start gap-3 rounded-xl border px-4 py-3 transition-colors"
            :class="item.alreadyPaid
              ? 'bg-surface-50 border-surface-100 opacity-60 cursor-not-allowed'
              : 'bg-surface-0 border-surface-200 cursor-pointer hover:bg-surface-50'"
            @click="toggleItem(beneficiaryUserId, item.paymentItemId, item.alreadyPaid)"
          >
            <!-- チェックボックス -->
            <Checkbox
              :model-value="isSelected(beneficiaryUserId, item.paymentItemId)"
              :disabled="item.alreadyPaid"
              :binary="true"
              @click.stop="toggleItem(beneficiaryUserId, item.paymentItemId, item.alreadyPaid)"
            />

            <!-- 会費情報 -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center justify-between gap-2">
                <p class="font-medium text-surface-800 truncate">{{ item.itemName }}</p>
                <span class="font-semibold text-surface-900 whitespace-nowrap">
                  ¥{{ item.totalCharge.toLocaleString() }}
                </span>
              </div>
              <div class="mt-0.5 flex items-center gap-2 flex-wrap">
                <!-- 所属 -->
                <span v-if="item.scopeName" class="text-xs text-surface-500">
                  {{ $t('payment.guardianBulkPayment.scope') }}: {{ item.scopeName }}
                </span>
                <!-- 種別 -->
                <Tag
                  :value="$t(`payment.guardianBulkPayment.kind.${item.kind}`)"
                  severity="secondary"
                  class="text-xs"
                />
                <!-- 期日 -->
                <span v-if="item.dueDate" class="text-xs text-surface-500">
                  {{ $t('payment.guardianBulkPayment.dueDate') }}: {{ item.dueDate }}
                </span>
              </div>
              <!-- 支払い済みバッジ -->
              <div v-if="item.alreadyPaid" class="mt-1">
                <Tag
                  :value="item.paidByDisplayName
                    ? $t('payment.guardianBulkPayment.paidBy', { name: item.paidByDisplayName })
                    : $t('payment.guardianBulkPayment.alreadyPaid')"
                  severity="success"
                  class="text-xs"
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- フッター（合計・支払いボタン） -->
      <div
        v-if="selectedCount > 0"
        class="sticky bottom-4 flex items-center justify-between rounded-xl bg-primary-600 text-white px-5 py-4 shadow-lg"
      >
        <span class="font-medium">
          {{ $t('payment.guardianBulkPayment.totalAmount', { amount: totalAmount.toLocaleString() }) }}
        </span>
        <Button
          :label="processing
            ? $t('payment.guardianBulkPayment.processing')
            : $t('payment.guardianBulkPayment.paySelected', { count: selectedCount })"
          severity="contrast"
          :loading="processing"
          :disabled="processing"
          @click="handleBulkCheckout"
        />
      </div>
    </template>

    <!-- 支払い結果ダイアログ -->
    <Dialog
      v-model:visible="showResult"
      modal
      :header="$t('payment.guardianBulkPayment.result.title')"
      :style="{ width: '28rem' }"
    >
      <div class="flex flex-col gap-2">
        <div
          v-for="result in results"
          :key="result.paymentItemId"
          class="flex items-center justify-between rounded-lg border px-3 py-2"
          :class="result.status === 'CHECKED_OUT'
            ? 'bg-green-50 border-green-200'
            : 'bg-surface-50 border-surface-200'"
        >
          <span class="text-sm text-surface-700 truncate mr-2">
            {{ getItemName(result.paymentItemId) }}
          </span>
          <div class="flex items-center gap-1 whitespace-nowrap">
            <Tag
              v-if="result.status === 'CHECKED_OUT'"
              :value="$t('payment.guardianBulkPayment.result.checkedOut')"
              severity="success"
              class="text-xs"
            />
            <template v-else>
              <Tag
                :value="$t('payment.guardianBulkPayment.result.skipped')"
                severity="secondary"
                class="text-xs"
              />
              <span v-if="result.skipReason" class="text-xs text-surface-500">
                ({{ getSkipReasonLabel(result.skipReason) }})
              </span>
            </template>
          </div>
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('common.close')"
          severity="secondary"
          @click="showResult = false"
        />
      </template>
    </Dialog>
  </div>
</template>
