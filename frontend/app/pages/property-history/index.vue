<script setup lang="ts">
/**
 * 物件履歴台帳 一覧ページ（F09.13 Phase 1-ε）。
 *
 * URL: /property-history?scope=teams|organizations&scopeId=N
 *
 * - 一覧 / タイムライン / ガント の 3 ビュー切替（PrimeVue SelectButton）
 * - フィルタ: workType / status / vendor / 期間
 * - 「新規工事を追加」モーダル（PropertyWorkPackageRequest を作成）
 * - PDF / Excel エクスポート（PropertyWorkExportButton）
 * - canEdit/canDelete 等は詳細遷移後に判定（ListView ではマスク済 actualAmount のみ）
 */
import type {
  PropertyWorkPackageRequest,
  PropertyWorkPackageSummaryResponse,
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
  return raw ? String(Array.isArray(raw) ? raw[0] : raw) : ''
})

const api = computed(() => usePropertyWorkPackageApi(scope.value, scopeId.value))

type View = 'list' | 'timeline' | 'gantt'
const view = ref<View>('list')
const viewOptions = computed(() => [
  { label: t('property.view.list'), value: 'list' as View },
  { label: t('property.view.timeline'), value: 'timeline' as View },
  { label: t('property.view.gantt'), value: 'gantt' as View },
])

// === Filters ===
const filterFrom = ref<Date | null>(null)
const filterTo = ref<Date | null>(null)
const filterWorkType = ref<WorkType | null>(null)
const filterStatus = ref<WorkPackageStatus | null>(null)
const filterVendorId = ref<number | null>(null)
const page = ref(0)
const size = ref(20)

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

const statusOptions = computed(() =>
  (
    ['PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CLOSED', 'CANCELLED'] as WorkPackageStatus[]
  ).map((v) => ({ label: t(`property.status.${v}`), value: v })),
)

function toIsoDate(d: Date | null): string | null {
  if (!d) return null
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const currentFilter = computed(() => ({
  from: toIsoDate(filterFrom.value),
  to: toIsoDate(filterTo.value),
  workType: filterWorkType.value,
  vendorId: filterVendorId.value,
  status: filterStatus.value,
  page: page.value,
  size: size.value,
}))

// === List state ===
const items = ref<PropertyWorkPackageSummaryResponse[]>([])
const totalElements = ref(0)
const loading = ref(false)

async function load() {
  if (scopeId.value === '') return
  loading.value = true
  try {
    if (view.value === 'list') {
      const res = await api.value.list(currentFilter.value)
      items.value = res.data
      totalElements.value = res.meta?.total ?? 0
    } else if (view.value === 'timeline') {
      const list = await api.value.getTimeline(
        toIsoDate(filterFrom.value),
        toIsoDate(filterTo.value),
      )
      items.value = list
      totalElements.value = list.length
    } else {
      // gantt: from/to は必須
      const today = new Date()
      const from = toIsoDate(filterFrom.value) ?? toIsoDate(new Date(today.getFullYear() - 1, 0, 1))
      const to = toIsoDate(filterTo.value) ?? toIsoDate(new Date(today.getFullYear() + 1, 11, 31))
      const list = await api.value.getGantt(from!, to!)
      items.value = list
      totalElements.value = list.length
    }
  } catch {
    showError(t('property.errors.loadFailed'))
  } finally {
    loading.value = false
  }
}

watch(
  () => [view.value, scope.value, scopeId.value],
  () => {
    page.value = 0
    load()
  },
)

watch([filterWorkType, filterStatus, filterVendorId, filterFrom, filterTo], () => {
  page.value = 0
  load()
})

watch(page, () => load())

onMounted(load)

function clearFilter() {
  filterFrom.value = null
  filterTo.value = null
  filterWorkType.value = null
  filterStatus.value = null
  filterVendorId.value = null
}

// === Create modal ===
const showCreateDialog = ref(false)
const creating = ref(false)
const draft = ref<PropertyWorkPackageRequest>(emptyDraft())

function emptyDraft(): PropertyWorkPackageRequest {
  return {
    workType: 'RENOVATION',
    title: '',
    isDisclosable: false,
    visibility: 'MEMBERS_MASKED',
    version: 0,
  }
}

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

function openCreate() {
  draft.value = emptyDraft()
  showCreateDialog.value = true
}

async function saveCreate() {
  if (!draft.value.title.trim()) {
    showError(t('validation.required'))
    return
  }
  creating.value = true
  try {
    await api.value.create(draft.value)
    showSuccess(t('property.saved'))
    showCreateDialog.value = false
    await load()
  } catch {
    showError(t('property.errors.saveFailed'))
  } finally {
    creating.value = false
  }
}

function onSelect(packageId: number) {
  navigateTo({
    path: `/property-history/${packageId}`,
    query: { scope: scope.value, scopeId: String(scopeId.value) },
  })
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <PageHeader :title="t('property.title')" />
        <p class="text-sm text-surface-500 dark:text-surface-400">
          {{ t('property.subtitle') }}
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <Button
          icon="pi pi-plus"
          :label="t('property.newPackage')"
          severity="primary"
          data-testid="property-new-package-btn"
          :disabled="scopeId === ''"
          @click="openCreate"
        />
        <PropertyWorkExportButton
          v-if="scopeId !== ''"
          :scope="scope"
          :scope-id="scopeId"
          :filter="currentFilter"
        />
      </div>
    </header>

    <p
      v-if="scopeId === ''"
      class="rounded-md border border-dashed border-yellow-300 bg-yellow-50 p-4 text-sm text-yellow-700 dark:border-yellow-800 dark:bg-yellow-950 dark:text-yellow-200"
    >
      ?scope=teams&scopeId=N
    </p>

    <section v-else class="space-y-4">
      <!-- View switcher -->
      <SelectButton
        v-model="view"
        :options="viewOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        data-testid="property-view-switch"
      />

      <!-- Filter bar -->
      <div class="grid grid-cols-1 gap-3 rounded-md border border-surface-200 p-3 md:grid-cols-2 lg:grid-cols-5 dark:border-surface-700">
        <Dropdown
          v-model="filterWorkType"
          :options="workTypeOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('property.filter.workType')"
          show-clear
          data-testid="property-filter-worktype"
        />
        <Dropdown
          v-model="filterStatus"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('property.filter.status')"
          show-clear
          data-testid="property-filter-status"
        />
        <VendorPicker
          v-model="filterVendorId"
          :scope="scope"
          :scope-id="scopeId"
        />
        <Calendar
          v-model="filterFrom"
          :placeholder="t('property.filter.from')"
          date-format="yy-mm-dd"
          show-icon
          show-button-bar
        />
        <Calendar
          v-model="filterTo"
          :placeholder="t('property.filter.to')"
          date-format="yy-mm-dd"
          show-icon
          show-button-bar
        />
        <div class="lg:col-span-5">
          <Button
            icon="pi pi-filter-slash"
            :label="t('property.filter.clear')"
            severity="secondary"
            text
            size="small"
            @click="clearFilter"
          />
        </div>
      </div>

      <!-- List -->
      <div
        v-if="loading"
        class="rounded-md border border-surface-200 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
      >
        {{ t('property.loading') }}
      </div>
      <div
        v-else-if="items.length === 0"
        class="rounded-md border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
      >
        {{ t('property.noPackages') }}
      </div>
      <ClientOnly v-else-if="view === 'gantt'">
        <PropertyWorkGanttView :packages="items" @select="onSelect" />
        <template #fallback>
          <div
            class="rounded-md border border-surface-200 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
          >
            {{ t('property.loading') }}
          </div>
        </template>
      </ClientOnly>
      <div v-else class="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
        <PropertyWorkPackageCard
          v-for="pkg in items"
          :key="pkg.id"
          :package="pkg"
          @select="onSelect"
        />
      </div>

      <!-- Pagination (list view only) -->
      <Paginator
        v-if="view === 'list' && totalElements > size"
        v-model:first="page"
        :rows="size"
        :total-records="totalElements"
        :rows-per-page-options="[10, 20, 50]"
        @update:rows="(v: number) => (size = v)"
      />
    </section>

    <!-- Create dialog -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="t('property.newPackage')"
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
            <label class="mb-1 block text-sm font-medium">{{ t('property.fields.category') }}</label>
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
          @click="showCreateDialog = false"
        />
        <Button
          :label="t('property.actions.save')"
          :loading="creating"
          severity="primary"
          @click="saveCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
