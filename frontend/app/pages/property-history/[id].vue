<script setup lang="ts">
/**
 * 物件履歴パッケージ 詳細ページ（F09.13 Phase 1-ε）。
 *
 * URL: /property-history/[id]?scope=teams|organizations&scopeId=N
 *
 * - パッケージ詳細表示（マスク済 amount, vendorNameSnapshot 等）
 * - 文書一覧（DocumentKind 別グループ）
 * - ステータス変更（PATCH）/ 編集モーダル / 削除（canDelete のみ）
 * - PDF/Excel 単独エクスポート
 * - 楽観的ロック競合（409）→ 最新版を再取得して再表示
 */
import type {
  PropertyWorkPackageRequest,
  PropertyWorkPackageResponse,
  ScopeName,
  WorkPackageStatus,
  WorkPackageVisibility,
  WorkType,
} from '~/types/property'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const { success: showSuccess, error: showError } = useNotification()

const scope = computed<ScopeName>(() => {
  const raw = (route.query.scope as string | undefined) ?? 'teams'
  return raw === 'organizations' ? 'organizations' : 'teams'
})
const scopeId = computed<string>(() => {
  const raw = route.query.scopeId
  return String(Array.isArray(raw) ? raw[0] : raw ?? '')
})
const packageId = computed<number>(() => Number(route.params.id))

const api = computed(() => usePropertyWorkPackageApi(scope.value, scopeId.value))

const detail = ref<PropertyWorkPackageResponse | null>(null)
const loading = ref(false)

async function load() {
  if (!scopeId.value || !Number.isFinite(packageId.value)) return
  loading.value = true
  try {
    detail.value = await api.value.get(packageId.value)
  } catch {
    showError(t('property.errors.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

// === 編集モーダル ===
const showEditDialog = ref(false)
const editing = ref(false)
const draft = ref<PropertyWorkPackageRequest | null>(null)

const workTypeOptions = computed(() =>
  (
    [
      'RENOVATION',
      'REPAIR',
      'INCIDENT',
      'INSPECTION',
      'DISASTER',
      'MEETING',
      'OTHER',
    ] as WorkType[]
  ).map((v) => ({ label: t(`property.workType.${v}`), value: v })),
)

const visibilityOptions = computed(() =>
  (
    [
      'ADMINS_ONLY',
      'MEMBERS_ONLY',
      'MEMBERS_MASKED',
      'PUBLIC_MASKED',
    ] as WorkPackageVisibility[]
  ).map((v) => ({ label: t(`property.visibility.${v}`), value: v })),
)

const statusOptions = computed(() =>
  (
    ['PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CLOSED', 'CANCELLED'] as WorkPackageStatus[]
  ).map((v) => ({ label: t(`property.status.${v}`), value: v })),
)

function openEdit() {
  if (!detail.value) return
  const d = detail.value
  draft.value = {
    dwellingUnitId: d.dwellingUnitId,
    workType: d.workType,
    category: d.category,
    title: d.title,
    description: d.description,
    incidentId: d.incidentId,
    incidentDate: d.incidentDate,
    incidentNarrative: d.incidentNarrative,
    plannedStartDate: d.plannedStartDate,
    plannedEndDate: d.plannedEndDate,
    actualStartDate: d.actualStartDate,
    actualEndDate: d.actualEndDate,
    vendorId: d.vendorId,
    estimatedAmount: d.estimatedAmount,
    contractAmount: d.contractAmount,
    actualAmount: d.actualAmount,
    currency: d.currency,
    budgetTransactionId: d.budgetTransactionId,
    warrantyUntil: d.warrantyUntil,
    isDisclosable: d.isDisclosable,
    visibility: d.visibility,
    tags: d.tags,
    version: d.version,
  }
  showEditDialog.value = true
}

async function saveEdit() {
  if (!draft.value) return
  if (!draft.value.title.trim()) {
    showError(t('validation.required'))
    return
  }
  editing.value = true
  try {
    detail.value = await api.value.update(packageId.value, draft.value)
    showSuccess(t('property.saved'))
    showEditDialog.value = false
  } catch (err) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      showError(t('property.errors.versionConflict'))
      // 最新版を再取得
      await load()
    } else {
      showError(t('property.errors.saveFailed'))
    }
  } finally {
    editing.value = false
  }
}

async function changeStatus(newStatus: WorkPackageStatus) {
  if (!detail.value) return
  try {
    detail.value = await api.value.changeStatus(packageId.value, { status: newStatus })
    showSuccess(t('property.saved'))
  } catch {
    showError(t('property.errors.saveFailed'))
  }
}

async function removePackage() {
  if (!detail.value) return
  if (!confirm(t('property.deleteConfirm'))) return
  try {
    await api.value.remove(packageId.value)
    showSuccess(t('property.deleted'))
    navigateTo({
      path: '/property-history',
      query: { scope: scope.value, scopeId: String(scopeId.value) },
    })
  } catch {
    showError(t('property.errors.deleteFailed'))
  }
}

async function detachDoc(documentId: number) {
  try {
    await api.value.detachDocument(packageId.value, documentId)
    await load()
    showSuccess(t('property.saved'))
  } catch {
    showError(t('property.errors.deleteFailed'))
  }
}

function back() {
  navigateTo({
    path: '/property-history',
    query: { scope: scope.value, scopeId: String(scopeId.value) },
  })
}

const formattedAmounts = computed(() => {
  if (!detail.value) return null
  const fmt = (n: number | null | undefined): string => {
    if (n === null || n === undefined) {
      return detail.value!.permissions.canViewAmount ? '-' : t('property.masked')
    }
    return n.toLocaleString('ja-JP')
  }
  return {
    estimated: fmt(detail.value.estimatedAmount),
    contract: fmt(detail.value.contractAmount),
    actual: fmt(detail.value.actualAmount),
  }
})
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <Button
        icon="pi pi-arrow-left"
        :label="t('property.actions.back')"
        severity="secondary"
        text
        @click="back"
      />
      <div v-if="detail" class="flex flex-wrap items-center gap-2">
        <PropertyWorkExportButton
          :scope="scope"
          :scope-id="scopeId"
          :package-id="detail.id"
        />
        <Button
          v-if="detail.permissions.canEdit"
          icon="pi pi-pencil"
          :label="t('property.actions.edit')"
          severity="primary"
          data-testid="property-edit-btn"
          @click="openEdit"
        />
        <Button
          v-if="detail.permissions.canDelete"
          icon="pi pi-trash"
          :label="t('property.actions.delete')"
          severity="danger"
          text
          data-testid="property-delete-btn"
          @click="removePackage"
        />
      </div>
    </header>

    <div v-if="loading" class="rounded-md border p-6 text-center text-sm text-surface-500">
      {{ t('property.loading') }}
    </div>

    <article v-else-if="detail" class="space-y-4">
      <Card>
        <template #title>
          <div class="flex flex-wrap items-center gap-2">
            <span
              class="rounded-full bg-indigo-100 px-2 py-0.5 text-xs font-medium text-indigo-700 dark:bg-indigo-900 dark:text-indigo-300"
            >
              {{ t(`property.workType.${detail.workType}`) }}
            </span>
            <Dropdown
              v-if="detail.permissions.canEdit"
              :model-value="detail.status"
              :options="statusOptions"
              option-label="label"
              option-value="value"
              size="small"
              data-testid="property-status-dropdown"
              @update:model-value="changeStatus"
            />
            <span
              v-else
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs font-medium text-surface-700 dark:bg-surface-800 dark:text-surface-300"
            >
              {{ t(`property.status.${detail.status}`) }}
            </span>
            <span
              v-if="detail.category"
              class="text-sm text-surface-500"
            >
              {{ detail.category }}
            </span>
          </div>
          <h2 class="mt-2 text-xl font-semibold">
            {{ detail.title }}
          </h2>
        </template>
        <template #content>
          <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
            <dl class="space-y-2 text-sm">
              <div>
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.vendor') }}
                </dt>
                <dd>{{ detail.vendorNameSnapshot ?? '-' }}</dd>
              </div>
              <div>
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.plannedStartDate') }} - {{ t('property.fields.plannedEndDate') }}
                </dt>
                <dd>{{ detail.plannedStartDate ?? '-' }} ~ {{ detail.plannedEndDate ?? '-' }}</dd>
              </div>
              <div>
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.actualStartDate') }} - {{ t('property.fields.actualEndDate') }}
                </dt>
                <dd>{{ detail.actualStartDate ?? '-' }} ~ {{ detail.actualEndDate ?? '-' }}</dd>
              </div>
              <div v-if="detail.warrantyUntil">
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.warrantyUntil') }}
                </dt>
                <dd>{{ detail.warrantyUntil }}</dd>
              </div>
            </dl>

            <dl class="space-y-2 text-sm">
              <div>
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.estimatedAmount') }}
                </dt>
                <dd class="tabular-nums">{{ formattedAmounts?.estimated }}</dd>
              </div>
              <div>
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.contractAmount') }}
                </dt>
                <dd class="tabular-nums">{{ formattedAmounts?.contract }}</dd>
              </div>
              <div>
                <dt class="font-medium text-surface-600 dark:text-surface-400">
                  {{ t('property.fields.actualAmount') }}
                </dt>
                <dd class="tabular-nums">{{ formattedAmounts?.actual }}</dd>
              </div>
              <div v-if="!detail.permissions.canViewAmount" class="text-xs text-surface-500">
                {{ t('property.permissions.amountMasked') }}
              </div>
            </dl>
          </div>

          <div v-if="detail.description" class="mt-4">
            <h3 class="mb-1 text-sm font-medium text-surface-600">
              {{ t('property.fields.description') }}
            </h3>
            <p class="whitespace-pre-wrap text-sm">{{ detail.description }}</p>
          </div>

          <div v-if="detail.incidentNarrative" class="mt-4">
            <h3 class="mb-1 text-sm font-medium text-surface-600">
              {{ t('property.fields.incidentNarrative') }}
            </h3>
            <p class="whitespace-pre-wrap text-sm">{{ detail.incidentNarrative }}</p>
          </div>

          <div v-if="detail.tags && detail.tags.length > 0" class="mt-4 flex flex-wrap gap-1">
            <span
              v-for="tag in detail.tags"
              :key="tag"
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-800"
            >
              #{{ tag }}
            </span>
          </div>
        </template>
      </Card>

      <Card>
        <template #title>{{ t('property.documents.title') }}</template>
        <template #content>
          <PropertyWorkDocumentList
            :documents="detail.documents"
            :can-edit="detail.permissions.canEdit"
            @detach="detachDoc"
          />
        </template>
      </Card>
    </article>

    <!-- 編集モーダル -->
    <Dialog
      v-if="draft"
      v-model:visible="showEditDialog"
      :header="t('property.editPackage')"
      modal
      :style="{ width: '40rem' }"
      :breakpoints="{ '768px': '90vw' }"
    >
      <div class="space-y-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.fields.title') }}</label>
          <InputText v-model="draft.title" class="w-full" />
        </div>

        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('property.workType.RENOVATION') }}</label>
            <Dropdown
              v-model="draft.workType"
              :options="workTypeOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('property.visibility.title') }}</label>
            <Dropdown
              v-model="draft.visibility"
              :options="visibilityOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.fields.category') }}</label>
          <InputText v-model="draft.category" class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.fields.description') }}</label>
          <Textarea v-model="draft.description" rows="3" class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.fields.vendor') }}</label>
          <VendorPicker
            v-model="draft.vendorId"
            :scope="scope"
            :scope-id="scopeId"
            :initial-name="detail?.vendorNameSnapshot ?? null"
          />
        </div>

        <div v-if="detail?.permissions.canViewAmount" class="grid grid-cols-1 gap-3 md:grid-cols-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('property.fields.estimatedAmount') }}</label>
            <InputNumber v-model="draft.estimatedAmount" class="w-full" :min="0" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('property.fields.contractAmount') }}</label>
            <InputNumber v-model="draft.contractAmount" class="w-full" :min="0" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('property.fields.actualAmount') }}</label>
            <InputNumber v-model="draft.actualAmount" class="w-full" :min="0" />
          </div>
        </div>

        <div>
          <label class="mb-1 flex items-center gap-2 text-sm font-medium">
            <Checkbox v-model="draft.isDisclosable" binary />
            {{ t('property.fields.isDisclosable') }}
          </label>
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('property.actions.cancel')"
          severity="secondary"
          text
          @click="showEditDialog = false"
        />
        <Button
          :label="t('property.actions.save')"
          :loading="editing"
          severity="primary"
          @click="saveEdit"
        />
      </template>
    </Dialog>
  </div>
</template>
