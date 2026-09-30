<script setup lang="ts">
/**
 * 価格改定（price-revisions）管理画面 — 一覧・検索・DRAFT作成・税コードマスタCRUD。
 *
 * 正本: `.claude/campaigns/price-rev-plan-v3.md` K群 AC-146〜AC-165。
 * `pages/system-admin/provisioning/index.vue` を金型に踏襲する。
 */
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import Dropdown from 'primevue/dropdown'
import InputNumber from 'primevue/inputnumber'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'

import type {
  BillingTaxCodeCreateRequest,
  BillingTaxCodeResponse,
  PriceRevisionBandInput,
  PriceRevisionProductKind,
  PriceRevisionStatus,
  PriceRevisionSummaryResponse,
} from '~/composables/useBillingApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const billingApi = useBillingApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { formatDateTime, buildOffsetDateTimeFromLocalInput } = useDatetime()

/**
 * `<input type="datetime-local">` の値（例 `2026-09-24T12:00`。オフセットを持たない）を、
 * BE の `Instant` が受理するオフセット付き ISO-8601 へ変換する。
 *
 * オフセット無しのまま送ると Jackson が `Instant` に解釈できず 400 になる。入力値は「画面で指した
 * 壁時計」なので、文字列の成分から直接ユーザーTZのオフセットを付ける（`new Date()` を経由すると
 * ブラウザTZの DST 境界で 1 時間ずれる。`formatDateTime` の表示と往復しても値がずれない）。
 *
 * ユーザーTZの DST 開始で**存在しない壁時計**（例: America/New_York の 2026-03-08 02:30）の場合は
 * `null` を返す（2026-09-24 検分指摘 P2）。呼び出し側は必ず `isNonexistentDstTime` でバリデーション
 * してから送信すること。
 */
function toInstantPayload(datetimeLocal: string): string | null {
  return buildOffsetDateTimeFromLocalInput(datetimeLocal)
}

/**
 * 入力済みの datetime-local 値が、ユーザーTZの DST 開始で存在しない壁時計かどうか。
 * フォームのバリデーション表示・送信ブロックに使う（黙って1時間ずれた値を送らないため）。
 */
function isNonexistentDstTime(datetimeLocal: string): boolean {
  if (!datetimeLocal) return false
  try {
    return buildOffsetDateTimeFromLocalInput(datetimeLocal) === null
  } catch {
    return false
  }
}

const isAllowed = computed(() => authStore.isSystemAdmin)

const STATUS_OPTIONS: PriceRevisionStatus[] = [
  'DRAFT', 'PROVISIONING', 'PROVISION_FAILED', 'READY', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'CANCELLED',
]

// ============================================================
// 一覧・検索・ページング
// ============================================================

const loading = ref(false)
const revisions = ref<PriceRevisionSummaryResponse[]>([])
const totalElements = ref(0)
const page = ref(0)
const pageSize = ref(20)
const filterStatus = ref<PriceRevisionStatus | null>(null)
const filterProductKey = ref('')

async function load() {
  loading.value = true
  try {
    const result = await billingApi.listPriceRevisions({
      status: filterStatus.value ?? undefined,
      productKey: filterProductKey.value || undefined,
      page: page.value,
      size: pageSize.value,
    })
    revisions.value = result.data.items
    totalElements.value = result.data.totalElements
  } catch (err) {
    console.error('price-revisions/index.vue: load failed', err)
    notification.error(t('billing.priceRevisions.loadFailed'))
    revisions.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

function goToDetail(row: PriceRevisionSummaryResponse) {
  navigateTo(`/system-admin/price-revisions/${row.id}`)
}

function statusSeverity(status: PriceRevisionStatus): 'success' | 'warn' | 'secondary' | 'danger' | 'info' {
  if (status === 'ACTIVE' || status === 'READY') return 'success'
  if (status === 'PROVISION_FAILED') return 'danger'
  if (status === 'PROVISIONING' || status === 'SCHEDULED') return 'warn'
  if (status === 'RETIRED' || status === 'CANCELLED') return 'secondary'
  return 'info'
}

// ============================================================
// DRAFT 作成
// ============================================================

const createDialogOpen = ref(false)
const creating = ref(false)

const form = reactive({
  productKind: 'PLAN' as PriceRevisionProductKind,
  productKey: '',
  scopeKind: 'USER' as 'USER' | 'TEAM' | 'ORG',
  effectiveFrom: '',
  effectiveUntil: '',
})

/** taxMode: 入力金額が税込か税抜か（未選択のまま送信させない・AC-153）。 */
const taxMode = ref<'EXCLUSIVE' | 'INCLUSIVE' | null>(null)

const bandForm = reactive<{ bandNo: number; minMembers: number; maxMembers: number | null; inputAmount: number | null; taxCode: string }>({
  bandNo: 1,
  minMembers: 1,
  maxMembers: null,
  inputAmount: null,
  taxCode: '',
})

/** 参考税率（画面プレビュー専用。確定値はサーバー側 BillingTaxDerivationService が計算する）。 */
const previewRateBasisPoints = ref(1000)

const taxExcludedPreview = computed<number>(() => {
  if (!bandForm.inputAmount || !taxMode.value) return 0
  if (taxMode.value === 'EXCLUSIVE') return bandForm.inputAmount
  return Math.round(bandForm.inputAmount / (1 + previewRateBasisPoints.value / 10000))
})

const taxAmountPreview = computed<number>(() => {
  if (!bandForm.inputAmount || !taxMode.value) return 0
  return Math.round(taxExcludedPreview.value * (previewRateBasisPoints.value / 10000))
})

const taxIncludedPreview = computed<number>(() => taxExcludedPreview.value + taxAmountPreview.value)

/** effectiveFrom が DST 開始で存在しない壁時計か（AC-158系・2026-09-24 検分指摘 P2）。 */
const effectiveFromDstInvalid = computed(() => isNonexistentDstTime(form.effectiveFrom))
/** effectiveUntil が DST 開始で存在しない壁時計か。未入力（任意項目）は無効扱いしない。 */
const effectiveUntilDstInvalid = computed(() => isNonexistentDstTime(form.effectiveUntil))

const canCreate = computed(() =>
  !creating.value
  && form.productKey.trim().length > 0
  && form.effectiveFrom.length > 0
  && !effectiveFromDstInvalid.value
  && !effectiveUntilDstInvalid.value
  && !!taxMode.value
  && !!bandForm.inputAmount
  && bandForm.taxCode.trim().length > 0)

function openCreateDialog() {
  form.productKind = 'PLAN'
  form.productKey = ''
  form.scopeKind = 'USER'
  form.effectiveFrom = ''
  form.effectiveUntil = ''
  taxMode.value = null
  bandForm.bandNo = 1
  bandForm.minMembers = 1
  bandForm.maxMembers = null
  bandForm.inputAmount = null
  bandForm.taxCode = ''
  createDialogOpen.value = true
}

function onCreateDialogEsc(event: KeyboardEvent) {
  if (event.key === 'Escape') createDialogOpen.value = false
}

async function createPriceRevision() {
  if (!canCreate.value || !taxMode.value || !bandForm.inputAmount) return
  const effectiveFromInstant = toInstantPayload(form.effectiveFrom)
  const effectiveUntilInstant = form.effectiveUntil ? toInstantPayload(form.effectiveUntil) : null
  // canCreate で既に弾いているはずだが、二重送信対策の防御としても null を送らない。
  if (effectiveFromInstant === null || (form.effectiveUntil && effectiveUntilInstant === null)) {
    notification.error(t('billing.priceRevisions.errorNonexistentDstTime'))
    return
  }
  creating.value = true
  try {
    const bands: PriceRevisionBandInput[] = [{
      bandNo: bandForm.bandNo,
      minMembers: bandForm.minMembers,
      maxMembers: bandForm.maxMembers,
      inputAmount: bandForm.inputAmount,
      taxBehavior: taxMode.value,
      taxCode: bandForm.taxCode.trim(),
    }]
    const result = await billingApi.createPriceRevision({
      productKind: form.productKind,
      productKey: form.productKey.trim(),
      scopeKind: form.scopeKind,
      effectiveFrom: effectiveFromInstant,
      effectiveUntil: effectiveUntilInstant,
      bands,
    })
    notification.success(t('billing.priceRevisions.createSuccess'))
    createDialogOpen.value = false
    // 作成直後は必ず 'DRAFT'。
    if (result.data.status === 'DRAFT') {
      await navigateTo(`/system-admin/price-revisions/${result.data.id}`)
    }
    await load()
  } catch (err) {
    console.error('price-revisions/index.vue: create failed', err)
    handleApiError(err, 'price-revisions-create')
  } finally {
    creating.value = false
  }
}

// ============================================================
// 税コードマスタ CRUD
// ============================================================

const taxCodesDialogOpen = ref(false)
const taxCodes = ref<BillingTaxCodeResponse[]>([])
const taxCodeLoading = ref(false)
const taxCodeSaving = ref(false)

const taxCodeForm = reactive<BillingTaxCodeCreateRequest>({
  code: '',
  displayName: '',
  rateBasisPoints: 1000,
  stripeTaxCode: '',
  validFrom: '',
  validUntil: null,
  enabled: true,
})

/** BE の Stripe 税コード形式検証（400 PRICE_REVISION_021: txcd_ + 数字8桁）に落ちたか。 */
function isInvalidStripeTaxCode(err: unknown): boolean {
  const apiError = err as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code === 'PRICE_REVISION_021'
}

async function loadTaxCodes() {
  taxCodeLoading.value = true
  try {
    taxCodes.value = await billingApi.listTaxCodes()
  } catch (err) {
    console.error('price-revisions/index.vue: loadTaxCodes failed', err)
    notification.error(t('billing.priceRevisions.loadFailed'))
  } finally {
    taxCodeLoading.value = false
  }
}

function openTaxCodesDialog() {
  taxCodesDialogOpen.value = true
  void loadTaxCodes()
}

/** 税コード validFrom が DST 開始で存在しない壁時計か。 */
const taxCodeValidFromDstInvalid = computed(() => isNonexistentDstTime(taxCodeForm.validFrom))

const taxCodeCreateDisabled = computed(() =>
  taxCodeSaving.value
  || !taxCodeForm.code.trim()
  || !taxCodeForm.displayName.trim()
  || !taxCodeForm.validFrom
  || taxCodeValidFromDstInvalid.value)

async function submitCreateTaxCode() {
  if (!taxCodeForm.code.trim() || !taxCodeForm.displayName.trim() || !taxCodeForm.validFrom) return
  const validFromInstant = toInstantPayload(taxCodeForm.validFrom)
  if (validFromInstant === null) {
    notification.error(t('billing.priceRevisions.errorNonexistentDstTime'))
    return
  }
  taxCodeSaving.value = true
  try {
    await billingApi.createTaxCode({
      ...taxCodeForm,
      code: taxCodeForm.code.trim(),
      validFrom: validFromInstant,
    })
    notification.success(t('billing.priceRevisions.taxCodeCreateSuccess'))
    taxCodeForm.code = ''
    taxCodeForm.displayName = ''
    taxCodeForm.stripeTaxCode = ''
    await loadTaxCodes()
  } catch (err) {
    console.error('price-revisions/index.vue: createTaxCode failed', err)
    if (isInvalidStripeTaxCode(err)) notification.error(t('billing.priceRevisions.errorInvalidStripeTaxCode'))
    else handleApiError(err, 'price-revisions-tax-code-create')
  } finally {
    taxCodeSaving.value = false
  }
}

async function updateTaxCode(row: BillingTaxCodeResponse, enabled: boolean) {
  try {
    await billingApi.updateTaxCode(row.id, {
      displayName: row.displayName,
      stripeTaxCode: row.stripeTaxCode,
      validUntil: row.validUntil,
      enabled,
    })
    await loadTaxCodes()
  } catch (err) {
    console.error('price-revisions/index.vue: updateTaxCode failed', err)
    if (isInvalidStripeTaxCode(err)) notification.error(t('billing.priceRevisions.errorInvalidStripeTaxCode'))
    else handleApiError(err, 'price-revisions-tax-code-update')
  }
}

async function deactivateTaxCode(row: BillingTaxCodeResponse) {
  try {
    await billingApi.deactivateTaxCode(row.id)
    await loadTaxCodes()
  } catch (err) {
    console.error('price-revisions/index.vue: deactivateTaxCode failed', err)
    handleApiError(err, 'price-revisions-tax-code-deactivate')
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
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('billing.priceRevisions.listTitle') }}
        </h1>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            :label="t('billing.priceRevisions.taxCodesTitle')"
            icon="pi pi-percentage"
            severity="secondary"
            aria-label="tax-codes"
            @click="openTaxCodesDialog"
          />
          <Button
            :label="t('billing.priceRevisions.createAction')"
            icon="pi pi-plus"
            aria-label="create-price-revision"
            :disabled="loading"
            @click="openCreateDialog"
          />
        </div>
      </header>

      <p class="rounded-md bg-surface-100 p-3 text-xs text-surface-600 dark:bg-surface-800 dark:text-surface-300">
        {{ t('billing.priceRevisions.notice.nextCycle') }}<br>
        {{ t('billing.priceRevisions.notice.noSaleOnPartialFailure') }}
      </p>

      <!-- 検索条件 -->
      <div class="flex flex-wrap items-end gap-3">
        <div>
          <label class="mb-1 block text-xs" for="pr-filter-product-key">{{ t('billing.priceRevisions.productKey') }}</label>
          <InputText id="pr-filter-product-key" v-model="filterProductKey" size="small" @keyup.enter="load" />
        </div>
        <div>
          <label class="mb-1 block text-xs" for="pr-filter-status">{{ t('billing.priceRevisions.status') }}</label>
          <Dropdown
            id="pr-filter-status"
            v-model="filterStatus"
            :options="STATUS_OPTIONS"
            show-clear
            class="w-48"
            aria-label="status-filter"
          />
        </div>
        <Button :label="t('button.search')" icon="pi pi-search" :disabled="loading" @click="page = 0; load()" />
      </div>

      <div v-if="loading" class="flex items-center justify-center py-12">
        <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
      </div>

      <!-- モバイル代替表示（375px 幅で横スクロールを起こさない・AC-164） -->
      <div v-if="!loading" class="space-y-2 sm:hidden">
        <button
          v-for="row in revisions"
          :key="row.id"
          type="button"
          class="w-full rounded-lg border border-surface-200 p-3 text-left dark:border-surface-700"
          :aria-label="`price-revision-${row.id}`"
          @click="goToDetail(row)"
        >
          <div class="flex items-center justify-between">
            <span class="font-medium">{{ row.productKey }}</span>
            <Tag :value="row.status" :severity="statusSeverity(row.status)" />
          </div>
          <p class="mt-1 text-xs text-surface-500">{{ formatDateTime(row.effectiveFrom) }}</p>
        </button>
      </div>

      <!-- デスクトップ表示 -->
      <DataTable
        v-if="!loading"
        :value="revisions"
        data-key="id"
        striped-rows
        class="hidden text-sm sm:block"
        @row-click="(e: { data: PriceRevisionSummaryResponse }) => goToDetail(e.data)"
      >
        <template #empty>
          <div class="flex flex-col items-center justify-center gap-3 py-12 text-surface-400">
            <i class="pi pi-inbox text-4xl" aria-hidden="true" />
            <p class="text-sm">{{ t('billing.priceRevisions.noData') }}</p>
          </div>
        </template>
        <Column :header="t('billing.priceRevisions.productKey')" field="productKey" />
        <Column :header="t('billing.priceRevisions.scopeKind')" field="scopeKind" />
        <Column :header="t('billing.priceRevisions.status')">
          <template #body="{ data: row }: { data: PriceRevisionSummaryResponse }">
            <Tag :value="row.status" :severity="statusSeverity(row.status)" />
          </template>
        </Column>
        <Column :header="t('billing.priceRevisions.effectiveFrom')">
          <template #body="{ data: row }: { data: PriceRevisionSummaryResponse }">
            {{ formatDateTime(row.effectiveFrom) }}
          </template>
        </Column>
        <Column :header="t('common.detail')" style="width: 8rem">
          <template #body="{ data: row }: { data: PriceRevisionSummaryResponse }">
            <Button
              icon="pi pi-arrow-right"
              text
              :aria-label="`detail-${row.id}`"
              @click.stop="goToDetail(row)"
            />
          </template>
        </Column>
      </DataTable>

      <div class="flex items-center justify-end gap-2 text-xs text-surface-500">
        <span>{{ totalElements }}</span>
        <Button icon="pi pi-chevron-left" text :disabled="page === 0 || loading" aria-label="prev-page" @click="page -= 1; load()" />
        <Button icon="pi pi-chevron-right" text :disabled="(page + 1) * pageSize >= totalElements || loading" aria-label="next-page" @click="page += 1; load()" />
      </div>
    </template>

    <!-- DRAFT 作成 Dialog -->
    <Dialog
      v-model:visible="createDialogOpen"
      modal
      :header="t('billing.priceRevisions.createTitle')"
      :style="{ width: '36rem' }"
      :draggable="false"
      @keydown="onCreateDialogEsc"
    >
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-xs" for="pr-product-kind">{{ t('billing.priceRevisions.productKind') }}</label>
          <Dropdown id="pr-product-kind" v-model="form.productKind" :options="['PLAN', 'ADDON']" class="w-full" aria-label="product-kind" />
        </div>
        <div>
          <label class="mb-1 block text-xs" for="pr-product-key">{{ t('billing.priceRevisions.productKey') }}</label>
          <InputText id="pr-product-key" v-model="form.productKey" class="w-full" aria-label="product-key" />
        </div>
        <div>
          <label class="mb-1 block text-xs" for="pr-scope-kind">{{ t('billing.priceRevisions.scopeKind') }}</label>
          <Dropdown id="pr-scope-kind" v-model="form.scopeKind" :options="['USER', 'TEAM', 'ORG']" class="w-full" aria-label="scope-kind" />
        </div>
        <div>
          <label class="mb-1 block text-xs" for="pr-effective-from">{{ t('billing.priceRevisions.effectiveFrom') }}</label>
          <input id="pr-effective-from" v-model="form.effectiveFrom" type="datetime-local" class="w-full rounded border p-2 text-sm" aria-label="effective-from">
          <p
            v-if="effectiveFromDstInvalid"
            class="mt-1 text-xs text-red-600 dark:text-red-400"
            aria-label="effective-from-dst-error"
          >
            {{ t('billing.priceRevisions.errorNonexistentDstTime') }}
          </p>
        </div>
        <div class="col-span-2">
          <label class="mb-1 block text-xs" for="pr-tax-mode">{{ t('billing.priceRevisions.taxBehavior') }}</label>
          <Dropdown
            id="pr-tax-mode"
            v-model="taxMode"
            :options="['EXCLUSIVE', 'INCLUSIVE']"
            :placeholder="t('billing.priceRevisions.taxBehavior')"
            class="w-full"
            aria-label="tax-mode"
          />
        </div>
        <div>
          <label class="mb-1 block text-xs" for="pr-input-amount">{{ t('billing.priceRevisions.inputAmount') }}</label>
          <InputNumber id="pr-input-amount" v-model="bandForm.inputAmount" class="w-full" aria-label="input-amount" />
        </div>
        <div>
          <label class="mb-1 block text-xs" for="pr-tax-code">{{ t('billing.priceRevisions.taxCode') }}</label>
          <InputText id="pr-tax-code" v-model="bandForm.taxCode" class="w-full" aria-label="tax-code" />
        </div>
      </div>

      <!-- 税額プレビュー（確定前確認・AC-154） -->
      <div class="mt-4 rounded-md border border-surface-200 p-3 text-sm dark:border-surface-700">
        <p class="mb-2 font-medium">{{ t('billing.priceRevisions.taxPreviewTitle') }}</p>
        <div class="grid grid-cols-3 gap-2 text-xs">
          <div>{{ t('billing.priceRevisions.amountExcludingTax') }}: {{ taxExcludedPreview }}</div>
          <div>{{ t('billing.priceRevisions.taxAmount') }}: {{ taxAmountPreview }}</div>
          <div>{{ t('billing.priceRevisions.amountIncludingTax') }}: {{ taxIncludedPreview }}</div>
        </div>
      </div>

      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="createDialogOpen = false" />
        <Button
          :label="t('billing.priceRevisions.createAction')"
          :loading="creating"
          :disabled="!canCreate || creating || !taxMode"
          aria-label="submit-create-price-revision"
          @click="createPriceRevision"
        />
      </template>
    </Dialog>

    <!-- 税コードマスタ Dialog -->
    <Dialog
      v-model:visible="taxCodesDialogOpen"
      modal
      :header="t('billing.priceRevisions.taxCodesTitle')"
      :style="{ width: '48rem' }"
      :draggable="false"
    >
      <div class="mb-4 grid grid-cols-5 items-end gap-2">
        <InputText v-model="taxCodeForm.code" :placeholder="t('billing.priceRevisions.taxCode')" aria-label="new-tax-code-code" />
        <InputText v-model="taxCodeForm.displayName" :placeholder="t('billing.priceRevisions.taxCode')" aria-label="new-tax-code-display-name" />
        <InputNumber v-model="taxCodeForm.rateBasisPoints" aria-label="new-tax-code-rate" />
        <div>
          <input v-model="taxCodeForm.validFrom" type="datetime-local" class="w-full rounded border p-2 text-sm" aria-label="new-tax-code-valid-from">
          <p
            v-if="taxCodeValidFromDstInvalid"
            class="mt-1 text-xs text-red-600 dark:text-red-400"
            aria-label="tax-code-valid-from-dst-error"
          >
            {{ t('billing.priceRevisions.errorNonexistentDstTime') }}
          </p>
        </div>
        <Button
          :label="t('billing.priceRevisions.taxCodeCreateAction')"
          :loading="taxCodeSaving"
          :disabled="taxCodeCreateDisabled"
          aria-label="submit-create-tax-code"
          @click="submitCreateTaxCode"
        />
      </div>
      <DataTable :value="taxCodes" :loading="taxCodeLoading" data-key="id" class="text-sm">
        <Column field="code" header="code" />
        <Column field="displayName" header="displayName" />
        <Column field="rateBasisPoints" header="rate" />
        <Column header="enabled">
          <template #body="{ data: row }: { data: BillingTaxCodeResponse }">
            <Button
              :label="row.enabled ? 'ON' : 'OFF'"
              text
              size="small"
              :aria-label="`toggle-tax-code-${row.id}`"
              @click="updateTaxCode(row, !row.enabled)"
            />
          </template>
        </Column>
        <Column>
          <template #body="{ data: row }: { data: BillingTaxCodeResponse }">
            <Button
              icon="pi pi-ban"
              text
              severity="danger"
              :aria-label="`deactivate-tax-code-${row.id}`"
              @click="deactivateTaxCode(row)"
            />
          </template>
        </Column>
      </DataTable>
    </Dialog>
  </div>
</template>
