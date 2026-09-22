<script setup lang="ts">
/**
 * 価格改定（price-revisions）詳細画面 — band別の成否・provision/retry/reconcile/activate 導線。
 *
 * 正本: `.claude/campaigns/price-rev-plan-v3.md` K群 AC-148/151/152/158/159/161/162/163。
 * `pages/system-admin/price-revisions/index.vue` を金型に踏襲する。
 *
 * AC-158: 業務再試行ボタン（onRetryProvisionClick 等）は押下ごとに新しい Idempotency-Key を
 * 発行する。一方、通信結果不明（ネットワーク断・タイムアウト等でレスポンスが受け取れない）の
 * 自動再送（autoResendOnNetworkError）は、同一操作の識別を壊さないよう同じ key を使い回す。
 *
 * AC-159: Stripe の生応答や決済用の一時シークレット等の秘密は画面・DOM・browser storage のどこにも
 * 保持・表示しない。band の表示に使うのは BE が返す安全なフィールド（status/errorCode/attempts等）
 * のみで、Stripe レスポンスをそのまま持ち回らない。
 */
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Tag from 'primevue/tag'

import type {
  PriceRevisionBandResponse,
  PriceRevisionResponse,
} from '~/composables/useBillingApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const billingApi = useBillingApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { formatDateTime } = useDatetime()
const route = useRoute()

const isAllowed = computed(() => authStore.isSystemAdmin)
const revisionId = computed(() => String(route.params.id))

// ============================================================
// 取得
// ============================================================

const loading = ref(false)
const revision = ref<PriceRevisionResponse | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await billingApi.getPriceRevision(revisionId.value)
    revision.value = result.data
  } catch (err) {
    console.error('price-revisions/[id].vue: load failed', err)
    notification.error(t('billing.priceRevisions.loadFailed'))
    revision.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)

function backToList() {
  navigateTo('/system-admin/price-revisions')
}

// ============================================================
// band 一覧・活性判定
// ============================================================

const bands = computed<PriceRevisionBandResponse[]>(() => revision.value?.bands ?? [])

/** AC-152: 全 band が READY のときだけ Activate が押せる。 */
const allBandsReady = computed(() =>
  bands.value.length > 0 && bands.value.every((b) => b.status === 'READY'))

const activateDisabledReason = computed(() =>
  allBandsReady.value ? '' : t('billing.priceRevisions.activateDisabledReason'))

/** AC-151: PROVISIONING のまま停滞している（=reconcile 導線を出す）状態かどうか。 */
const showReconcile = computed(() =>
  revision.value?.status === 'PROVISIONING' || revision.value?.status === 'PROVISION_FAILED')

function statusSeverity(status: string): 'success' | 'warn' | 'secondary' | 'danger' | 'info' {
  if (status === 'ACTIVE' || status === 'READY') return 'success'
  if (status === 'PROVISION_FAILED') return 'danger'
  if (status === 'PROVISIONING' || status === 'SCHEDULED') return 'warn'
  if (status === 'RETIRED') return 'secondary'
  return 'info'
}

/**
 * AC-161: エラーコード別に別々の文言キーへ振り分ける（overlap/CAS競合/税コード不正/
 * RECONCILE_ATTRIBUTE_MISMATCH/PROCESSING を判別）。band.provisionErrorCode は
 * `PriceRevisionErrorCode` の enum 名（例: RECONCILE_ATTRIBUTE_MISMATCH）、
 * BE 例外の簡易メッセージ、または PROCESSING（冪等 lease 保持中）のいずれかが入る。
 */
function bandErrorMessage(code?: string | null): string {
  if (!code) return ''
  if (code.includes('REVISION_OVERLAP') || code.includes('OVERLAP')) {
    return t('billing.priceRevisions.errorOverlap')
  }
  if (code.includes('LOCK_VERSION_CONFLICT')) {
    return t('billing.priceRevisions.errorLockVersionConflict')
  }
  if (code.includes('TAX_CODE_NOT_FOUND') || code.includes('INVALID_TAX_CODE')) {
    return t('billing.priceRevisions.errorInvalidTaxCode')
  }
  if (code.includes('RECONCILE_ATTRIBUTE_MISMATCH')) {
    return t('billing.priceRevisions.errorReconcileMismatch')
  }
  if (code.includes('PROCESSING') || code.includes('PROVISION_IN_PROGRESS')) {
    return t('billing.priceRevisions.errorProvisionInProgress')
  }
  return t('billing.priceRevisions.errorStateConflict')
}

/** API 呼び出し失敗時（トースト表示用）も同じ振り分けを使う。 */
function apiErrorMessage(err: unknown): string | null {
  const apiError = err as { data?: { error?: { code?: string } } }
  const code = apiError?.data?.error?.code
  if (!code) return null
  return bandErrorMessage(code)
}

// ============================================================
// ネットワーク断検知・Idempotency-Key の使い分け（AC-158）
// ============================================================

/** レスポンスを受け取れず通信結果が不明なエラーかどうか（HTTPステータスが無い＝未達）。 */
function isNetworkError(err: unknown): boolean {
  const apiError = err as { statusCode?: number; status?: number; data?: unknown }
  return apiError?.statusCode === undefined && apiError?.status === undefined && apiError?.data === undefined
}

/**
 * 通信結果不明（ネットワーク断・タイムアウト）の自動再送。同一 Idempotency-Key を使い回すことで
 * サーバー側の冪等判定を壊さない（1回だけ自動再送し、それでも失敗すれば呼び出し元に例外を返す）。
 */
async function autoResendOnNetworkError(
  action: (key: string) => Promise<PriceRevisionResponse>,
  key: string,
): Promise<PriceRevisionResponse> {
  try {
    return await action(key)
  } catch (err) {
    if (isNetworkError(err)) {
      // 同一キーで1回だけ自動再送する。
      return action(key)
    }
    throw err
  }
}

// ============================================================
// Provision / Retry / Reconcile / Activate（AC-148/151/152）
// ============================================================

const provisioning = ref(false)
const retrying = ref(false)
const reconciling = ref(false)
const activating = ref(false)
const busy = computed(() => loading.value || provisioning.value || retrying.value || reconciling.value || activating.value)

async function onProvisionClick() {
  if (!revision.value || busy.value) return
  provisioning.value = true
  const key = crypto.randomUUID()
  try {
    const result = await autoResendOnNetworkError(
      (idempotencyKey) => billingApi.provisionPriceRevision(revision.value!.id, revision.value!.lockVersion ?? 0, idempotencyKey)
        .then((r) => r.data),
      key,
    )
    revision.value = result
    notification.success(t('billing.priceRevisions.provisionSuccess'))
  } catch (err) {
    console.error('price-revisions/[id].vue: provision failed', err)
    const mapped = apiErrorMessage(err)
    if (mapped) notification.error(mapped)
    else handleApiError(err, 'price-revisions-provision')
  } finally {
    provisioning.value = false
  }
}

/** AC-158: 業務再試行ボタン。押下のたびに新しい Idempotency-Key を発行する。 */
async function onRetryProvisionClick() {
  if (!revision.value || busy.value) return
  retrying.value = true
  const key = crypto.randomUUID()
  try {
    const result = await autoResendOnNetworkError(
      (idempotencyKey) => billingApi.retryProvisionPriceRevision(revision.value!.id, revision.value!.lockVersion ?? 0, idempotencyKey)
        .then((r) => r.data),
      key,
    )
    revision.value = result
    notification.success(t('billing.priceRevisions.retryProvisionSuccess'))
  } catch (err) {
    console.error('price-revisions/[id].vue: retry-provision failed', err)
    const mapped = apiErrorMessage(err)
    if (mapped) notification.error(mapped)
    else handleApiError(err, 'price-revisions-retry-provision')
  } finally {
    retrying.value = false
  }
}

async function onReconcileClick() {
  if (!revision.value || busy.value) return
  reconciling.value = true
  const key = crypto.randomUUID()
  try {
    const result = await autoResendOnNetworkError(
      (idempotencyKey) => billingApi.reconcileProvisionPriceRevision(revision.value!.id, revision.value!.lockVersion ?? 0, idempotencyKey)
        .then((r) => r.data),
      key,
    )
    revision.value = result
    notification.success(t('billing.priceRevisions.reconcileProvisionSuccess'))
  } catch (err) {
    console.error('price-revisions/[id].vue: reconcile-provision failed', err)
    const mapped = apiErrorMessage(err)
    if (mapped) notification.error(mapped)
    else handleApiError(err, 'price-revisions-reconcile-provision')
  } finally {
    reconciling.value = false
  }
}

async function onActivateClick() {
  if (!revision.value || busy.value || !allBandsReady.value) return
  if (!window.confirm(t('billing.priceRevisions.confirmActivate'))) return
  activating.value = true
  const key = crypto.randomUUID()
  try {
    const result = await autoResendOnNetworkError(
      (idempotencyKey) => billingApi.activatePriceRevision(revision.value!.id, revision.value!.lockVersion ?? 0, idempotencyKey)
        .then((r) => r.data),
      key,
    )
    revision.value = result
    notification.success(t('billing.priceRevisions.activateSuccess'))
  } catch (err) {
    console.error('price-revisions/[id].vue: activate failed', err)
    const mapped = apiErrorMessage(err)
    if (mapped) notification.error(mapped)
    else handleApiError(err, 'price-revisions-activate')
  } finally {
    activating.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <div
      v-if="!isAllowed"
      class="flex flex-col items-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400"
    >
      <i class="pi pi-lock text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('billing.priceRevisions.noPermission') }}</p>
    </div>

    <template v-else>
      <header class="flex flex-wrap items-center justify-between gap-2">
        <div class="flex items-center gap-2">
          <Button
            icon="pi pi-arrow-left"
            text
            aria-label="back-to-list"
            :label="t('billing.priceRevisions.backToList')"
            @click="backToList"
          />
        </div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('billing.priceRevisions.detailTitle') }}
        </h1>
      </header>

      <div v-if="loading" class="flex items-center justify-center py-12">
        <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
      </div>

      <template v-else-if="revision">
        <section class="grid grid-cols-2 gap-3 rounded-md border border-surface-200 p-4 text-sm dark:border-surface-700 md:grid-cols-4">
          <div>
            <span class="block text-xs text-surface-500">{{ t('billing.priceRevisions.productKey') }}</span>
            <span>{{ revision.productKey }}</span>
          </div>
          <div>
            <span class="block text-xs text-surface-500">{{ t('billing.priceRevisions.scopeKind') }}</span>
            <span>{{ revision.scopeKind }}</span>
          </div>
          <div>
            <span class="block text-xs text-surface-500">{{ t('billing.priceRevisions.status') }}</span>
            <Tag :value="revision.status" :severity="statusSeverity(revision.status)" />
          </div>
          <div>
            <span class="block text-xs text-surface-500">{{ t('billing.priceRevisions.effectiveFrom') }}</span>
            <span>{{ formatDateTime(revision.effectiveFrom) }}</span>
          </div>
        </section>

        <!-- band別成否・エラー・試行回数（AC-148） -->
        <DataTable :value="bands" data-key="id" striped-rows class="text-sm">
          <template #empty>
            <p class="py-8 text-center text-surface-400">{{ t('billing.priceRevisions.noData') }}</p>
          </template>
          <Column :header="t('billing.priceRevisions.bandNo')" field="bandNo" />
          <Column :header="t('billing.priceRevisions.status')">
            <template #body="{ data: row }: { data: PriceRevisionBandResponse }">
              <Tag :value="row.status" :severity="statusSeverity(row.status)" />
            </template>
          </Column>
          <Column :header="t('billing.priceRevisions.amountIncludingTax')" field="amountIncludingTax" />
          <Column :header="t('billing.priceRevisions.attemptCount')">
            <template #body="{ data: row }: { data: PriceRevisionBandResponse }">
              <span data-testid="band-attempt-count">{{ row.provisionAttempts }}</span>
            </template>
          </Column>
          <Column :header="t('billing.priceRevisions.provisionErrorLabel')">
            <template #body="{ data: row }: { data: PriceRevisionBandResponse }">
              <span
                v-if="row.provisionErrorCode"
                class="text-xs text-red-600 dark:text-red-400"
                :aria-label="`band-error-${row.bandNo}`"
              >{{ bandErrorMessage(row.provisionErrorCode) }}</span>
            </template>
          </Column>
        </DataTable>

        <!-- PROVISIONING 停滞時の reconcile 導線（AC-151） -->
        <p v-if="revision.status === 'PROVISIONING'" class="text-xs text-surface-500">
          {{ t('billing.priceRevisions.status') }}: PROVISIONING
        </p>

        <!-- 操作導線: provision / retry-provision / reconcile-provision / activate -->
        <div class="flex flex-wrap items-center gap-2">
          <Button
            :label="t('billing.priceRevisions.provisionAction')"
            icon="pi pi-play"
            aria-label="provision-price-revision"
            :loading="provisioning"
            :disabled="loading || busy"
            @click="onProvisionClick"
          />
          <Button
            :label="t('billing.priceRevisions.retryProvisionAction')"
            icon="pi pi-refresh"
            severity="secondary"
            aria-label="retry-provision-price-revision"
            :loading="retrying"
            :disabled="busy"
            @click="onRetryProvisionClick"
          />
          <Button
            v-if="showReconcile"
            :label="t('billing.priceRevisions.reconcileProvisionAction')"
            icon="pi pi-sync"
            severity="secondary"
            aria-label="reconcile-provision-price-revision"
            :loading="reconciling"
            :disabled="busy"
            @click="onReconcileClick"
          />
          <Button
            :label="t('billing.priceRevisions.activateAction')"
            icon="pi pi-check"
            severity="success"
            aria-label="activate-price-revision"
            :loading="activating"
            :disabled="!allBandsReady || loading || busy"
            @click="onActivateClick"
          />
        </div>
        <p v-if="!allBandsReady" class="text-xs text-surface-500" aria-label="activate-disabled-reason">
          {{ activateDisabledReason }}
        </p>
      </template>
    </template>
  </div>
</template>
