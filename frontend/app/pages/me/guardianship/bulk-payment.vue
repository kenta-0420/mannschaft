<script setup lang="ts">
import type { PayableDueItem } from '~/types/payment'

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
const route = useRoute()
const router = useRouter()
const payableDuesApi = usePayableDuesApi()
const paymentApi = usePaymentApi()
const { retrievePaymentIntent } = useStripeSetup()
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
const showResult = ref(false)
const showConfirm = ref(false)
const activeCheckout = ref<{ item: PayableDueItem; clientSecret: string; memberPaymentId: number } | null>(null)
const pendingQueue = ref<PayableDueItem[]>([])
const runItems = ref<PayableDueItem[]>([])
type RunStatus = 'PAID' | 'REFLECTING' | 'FAILED' | 'UNPROCESSED'
const runStatuses = ref<Record<string, RunStatus>>({})
const paymentError = ref<string | null>(null)

const BATCH_STORAGE_KEY = 'cmp011:payment:batch'
const CURRENT_STORAGE_KEY = 'cmp011:payment:current'
interface StoredCurrentCheckout {
  selectionKey: SelectionKey
  memberPaymentId: number
  item: PayableDueItem
}

const paymentReturnUrl = computed(() => {
  if (typeof window === 'undefined') return ''
  return new URL(route.path, window.location.origin).toString()
})

function idempotencyStorageKey(item: PayableDueItem) {
  return `cmp011:payment:${item.beneficiaryUserId}:${item.paymentItemId}`
}
function getIdempotencyKey(item: PayableDueItem): string {
  const key = idempotencyStorageKey(item)
  const existing = sessionStorage.getItem(key)
  if (existing) return existing
  const value = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`
  sessionStorage.setItem(key, value)
  return value
}

function persistBatch() {
  sessionStorage.setItem(BATCH_STORAGE_KEY, JSON.stringify(pendingQueue.value.map(item =>
    makeKey(item.beneficiaryUserId, item.paymentItemId))))
}

function restoreBatchKeys(): SelectionKey[] {
  try {
    const parsed: unknown = JSON.parse(sessionStorage.getItem(BATCH_STORAGE_KEY) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter((key): key is SelectionKey =>
      typeof key === 'string' && /^\d+:\d+$/.test(key))
  } catch {
    sessionStorage.removeItem(BATCH_STORAGE_KEY)
    return []
  }
}

function restoreCurrentCheckout(): StoredCurrentCheckout | null {
  try {
    return JSON.parse(sessionStorage.getItem(CURRENT_STORAGE_KEY) ?? 'null') as StoredCurrentCheckout | null
  } catch {
    sessionStorage.removeItem(CURRENT_STORAGE_KEY)
    return null
  }
}

function replaceRunStatus(key: SelectionKey, status: RunStatus) {
  runStatuses.value = { ...runStatuses.value, [key]: status }
}
function selectedItems(): PayableDueItem[] {
  return [...selected.value].map((key) => {
    const [beneficiary, itemId] = key.split(':').map(Number)
    return items.value.find((item) => item.beneficiaryUserId === beneficiary && item.paymentItemId === itemId)
  }).filter((item): item is PayableDueItem => Boolean(item))
}
function openCheckoutConfirmation() {
  if (selectedCount.value > 0) showConfirm.value = true
}
function confirmCheckout() {
  showConfirm.value = false
  runItems.value = selectedItems()
  pendingQueue.value = [...runItems.value]
  runStatuses.value = Object.fromEntries(runItems.value.map(item =>
    [makeKey(item.beneficiaryUserId, item.paymentItemId), 'UNPROCESSED']))
  paymentError.value = null
  persistBatch()
  void processNext()
}

async function stopRun(message: string) {
  paymentError.value = message
  activeCheckout.value = null
  processing.value = false
  persistBatch()
  await loadItems()
  showResult.value = true
}

async function processNext() {
  const next = pendingQueue.value[0]
  if (!next) {
    sessionStorage.removeItem(BATCH_STORAGE_KEY)
    sessionStorage.removeItem(CURRENT_STORAGE_KEY)
    processing.value = false
    await loadItems()
    showResult.value = true
    return
  }
  processing.value = true
  const key = makeKey(next.beneficiaryUserId, next.paymentItemId)
  try {
    const res = await paymentApi.createConnectCheckout(next.paymentItemId, next.beneficiaryUserId, getIdempotencyKey(next))
    if (!res.data.clientSecret) {
      replaceRunStatus(key, 'FAILED')
      await stopRun(t('payment.guardianBulkPayment.paymentError'))
      return
    }
    const stored: StoredCurrentCheckout = {
      selectionKey: key,
      memberPaymentId: res.data.memberPaymentId,
      item: next,
    }
    sessionStorage.setItem(CURRENT_STORAGE_KEY, JSON.stringify(stored))
    activeCheckout.value = {
      item: next,
      clientSecret: res.data.clientSecret,
      memberPaymentId: res.data.memberPaymentId,
    }
  } catch (error) {
    replaceRunStatus(key, 'FAILED')
    await stopRun(error instanceof Error ? error.message : t('payment.guardianBulkPayment.paymentError'))
  }
}

async function finalizeCurrentPayment(item: PayableDueItem, memberPaymentId: number) {
  const key = makeKey(item.beneficiaryUserId, item.paymentItemId)
  const deadline = Date.now() + 10_000
  let paid = false
  while (Date.now() < deadline) {
    const res = await paymentApi.getConnectCheckoutStatus(item.paymentItemId, memberPaymentId)
    paid = res.data.status === 'PAID'
    if (paid) break
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  if (paid) {
    replaceRunStatus(key, 'PAID')
    sessionStorage.removeItem(idempotencyStorageKey(item))
    selected.value = new Set([...selected.value].filter(selectedKey => selectedKey !== key))
    pendingQueue.value = pendingQueue.value.filter(due => makeKey(due.beneficiaryUserId, due.paymentItemId) !== key)
    sessionStorage.removeItem(CURRENT_STORAGE_KEY)
    activeCheckout.value = null
    persistBatch()
    await processNext()
  } else {
    replaceRunStatus(key, 'REFLECTING')
    await stopRun(t('payment.guardianBulkPayment.webhookPending'))
  }
}

async function onPaymentSuccess() {
  const current = activeCheckout.value
  if (current) await finalizeCurrentPayment(current.item, current.memberPaymentId)
}
function onPaymentError(message: string) {
  const current = activeCheckout.value
  if (!current) return
  replaceRunStatus(makeKey(current.item.beneficiaryUserId, current.item.paymentItemId), 'FAILED')
  void stopRun(message)
}

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
  openCheckoutConfirmation()
}

// ── 結果表示ユーティリティ ────────────────────────────────────

function resultStatus(item: PayableDueItem): RunStatus {
  return runStatuses.value[makeKey(item.beneficiaryUserId, item.paymentItemId)] ?? 'UNPROCESSED'
}

function resultSeverity(status: RunStatus): 'success' | 'warn' | 'danger' | 'secondary' {
  if (status === 'PAID') return 'success'
  if (status === 'REFLECTING') return 'warn'
  if (status === 'FAILED') return 'danger'
  return 'secondary'
}

// ── 初期化 ────────────────────────────────────────────────────

function restoreBatchItems(): SelectionKey[] {
  const storedKeys = restoreBatchKeys()
  runItems.value = storedKeys.map(key => {
    const [beneficiaryUserId, paymentItemId] = key.split(':').map(Number)
    return items.value.find(item => item.beneficiaryUserId === beneficiaryUserId
      && item.paymentItemId === paymentItemId)
  }).filter((item): item is PayableDueItem => Boolean(item))
  runStatuses.value = Object.fromEntries(runItems.value.map(item => [
    makeKey(item.beneficiaryUserId, item.paymentItemId),
    item.alreadyPaid ? 'PAID' : 'UNPROCESSED',
  ]))
  for (const item of runItems.value.filter(item => item.alreadyPaid)) {
    sessionStorage.removeItem(idempotencyStorageKey(item))
  }
  pendingQueue.value = runItems.value.filter(item => !item.alreadyPaid)
  selected.value = new Set(pendingQueue.value.map(item =>
    makeKey(item.beneficiaryUserId, item.paymentItemId)))
  return storedKeys
}

async function handlePaymentRedirectReturn() {
  const secretParam = route.query.payment_intent_client_secret
  const clientSecret = Array.isArray(secretParam) ? secretParam[0] : secretParam
  if (!clientSecret) return

  try {
    await router.replace({ path: route.path })
  } catch (error) {
    console.warn('[bulk-payment] PaymentIntent secret query の除去に失敗', {
      path: route.path,
      reason: error instanceof Error ? error.message : String(error),
    })
  }

  restoreBatchItems()

  const storedCurrent = restoreCurrentCheckout()
  if (!storedCurrent?.item || !storedCurrent.memberPaymentId) {
    await stopRun(t('payment.guardianBulkPayment.paymentError'))
    return
  }
  const current = storedCurrent.item

  processing.value = true
  const result = await retrievePaymentIntent(clientSecret)
  if (result.status === 'error') {
    replaceRunStatus(makeKey(current.beneficiaryUserId, current.paymentItemId), 'FAILED')
    await stopRun(result.message)
    return
  }
  if (result.paymentIntent.status === 'succeeded' || result.paymentIntent.status === 'processing') {
    await finalizeCurrentPayment(current, storedCurrent.memberPaymentId)
    return
  }
  replaceRunStatus(makeKey(current.beneficiaryUserId, current.paymentItemId), 'FAILED')
  await stopRun(t('payment.guardianBulkPayment.paymentError'))
}

onMounted(async () => {
  await loadItems()
  if (route.query.payment_intent_client_secret) {
    await handlePaymentRedirectReturn()
    return
  }
  if (restoreBatchKeys().length > 0) {
    restoreBatchItems()
    const storedCurrent = restoreCurrentCheckout()
    if (storedCurrent?.item && storedCurrent.memberPaymentId
      && !runItems.value.some(item => makeKey(item.beneficiaryUserId, item.paymentItemId) === storedCurrent.selectionKey)) {
      runItems.value = [storedCurrent.item, ...runItems.value]
      pendingQueue.value = [storedCurrent.item, ...pendingQueue.value]
      replaceRunStatus(storedCurrent.selectionKey, 'UNPROCESSED')
      await finalizeCurrentPayment(storedCurrent.item, storedCurrent.memberPaymentId)
      return
    }
    if (pendingQueue.value.length > 0) await processNext()
    else {
      sessionStorage.removeItem(BATCH_STORAGE_KEY)
      showResult.value = true
    }
  }
})
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

    <Dialog v-model:visible="showConfirm" modal :header="$t('payment.guardianBulkPayment.confirmTitle')" :style="{ width: '30rem' }">
      <p>{{ $t('payment.guardianBulkPayment.confirmCount', { count: selectedCount }) }}</p>
      <ul class="my-3 list-disc pl-5">
        <li v-for="item in selectedItems()" :key="`${item.beneficiaryUserId}:${item.paymentItemId}`">
          {{ item.beneficiaryDisplayName ?? item.beneficiaryUserId }} —
          {{ item.scopeName ?? $t('payment.guardianBulkPayment.unknownRecipient') }} —
          {{ item.itemName }} (¥{{ item.totalCharge.toLocaleString() }})
        </li>
      </ul>
      <p>{{ $t('payment.guardianBulkPayment.confirmTotal', { amount: totalAmount.toLocaleString() }) }}</p>
      <p class="mt-2 text-sm text-surface-500">{{ $t('payment.guardianBulkPayment.confirmDisclosure') }}</p>
      <template #footer>
        <Button :label="$t('common.cancel')" severity="secondary" @click="showConfirm = false" />
        <Button :label="$t('payment.guardianBulkPayment.confirmStart')" @click="confirmCheckout" />
      </template>
    </Dialog>

    <Dialog :visible="Boolean(activeCheckout)" modal :closable="false" :header="$t('payment.guardianBulkPayment.paymentTitle')" :style="{ width: '32rem' }">
      <p v-if="activeCheckout" class="mb-3">{{ activeCheckout.item.itemName }} — {{ activeCheckout.item.beneficiaryDisplayName ?? activeCheckout.item.beneficiaryUserId }}</p>
      <StripePaymentForm
        v-if="activeCheckout"
        :client-secret="activeCheckout.clientSecret"
        mode="payment"
        :return-url="paymentReturnUrl"
        @success="onPaymentSuccess"
        @error="onPaymentError"
      />
    </Dialog>

    <!-- 支払い結果ダイアログ -->
    <Dialog
      v-model:visible="showResult"
      modal
      :header="$t('payment.guardianBulkPayment.result.title')"
      :style="{ width: '28rem' }"
    >
      <div class="flex flex-col gap-2">
        <div
          v-for="item in runItems"
          :key="`${item.beneficiaryUserId}:${item.paymentItemId}`"
          class="flex items-center justify-between rounded-lg border px-3 py-2"
          :class="resultStatus(item) === 'PAID'
            ? 'bg-green-50 border-green-200'
            : 'bg-surface-50 border-surface-200'"
        >
          <span class="text-sm text-surface-700 truncate mr-2">
            {{ item.beneficiaryDisplayName ?? item.beneficiaryUserId }} — {{ item.itemName }}
          </span>
          <div class="flex items-center gap-1 whitespace-nowrap">
            <Tag
              :value="$t(`payment.guardianBulkPayment.result.status.${resultStatus(item)}`)"
              :severity="resultSeverity(resultStatus(item))"
              class="text-xs"
            />
          </div>
        </div>
        <Message v-if="paymentError" severity="warn" :closable="false" class="mt-2">
          {{ paymentError }}
        </Message>
        <p v-if="runItems.some(item => resultStatus(item) !== 'PAID')" class="text-sm text-surface-500">
          {{ $t('payment.guardianBulkPayment.result.retryNotice') }}
        </p>
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
