<script setup lang="ts">
/**
 * 業者マスタ管理ページ（F09.13 Phase 1-ε）。
 *
 * URL: /admin/vendors?scope=teams|organizations&scopeId=N
 *
 * - 業者一覧（PrimeVue DataTable）
 * - 新規/編集/削除ダイアログ
 * - カテゴリフィルタ・名前/カナ検索
 * - 有効/無効フラグ表示
 *
 * Phase 1 では admin guard は middleware:'auth' のみ。ロール検証は今後の中央化に従う
 * （バックエンドの @PreAuthorize で 403 を返すため、UI 側で誤って表示しても
 * API 呼び出しは安全に拒否される）。
 */
import type {
  VendorCategory,
  VendorRequest,
  VendorResponse,
} from '~/types/vendor'
import type { ScopeName } from '~/types/property'

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

const api = computed(() => useVendorApi(scope.value, scopeId.value))

const items = ref<VendorResponse[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const page = ref(0)
const size = ref(20)
const search = ref('')
const filterCategory = ref<VendorCategory | null>(null)

const categoryOptions = computed(() =>
  (
    [
      'CONSTRUCTION',
      'INSPECTION',
      'CONSULTING',
      'CLEANING',
      'SECURITY',
      'OTHER',
    ] as VendorCategory[]
  ).map((v) => ({ label: t(`property.vendor.category.${v}`), value: v })),
)

async function load() {
  if (scopeId.value === '') return
  loading.value = true
  try {
    const params: Parameters<typeof api.value.list>[0] = {
      page: page.value,
      size: size.value,
    }
    if (search.value.trim()) params.q = search.value.trim()
    if (filterCategory.value) params.category = filterCategory.value
    const res = await api.value.list(params)
    items.value = res.data
    totalRecords.value = res.meta?.total ?? res.data.length
  } catch {
    showError(t('property.errors.loadFailed'))
  } finally {
    loading.value = false
  }
}

watch([scope, scopeId, filterCategory], () => {
  page.value = 0
  load()
})

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(search, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 0
    load()
  }, 300)
})

watch(page, () => load())

onMounted(load)

// === Edit / Create dialog ===
const showDialog = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const draft = ref<VendorRequest>(emptyDraft())

function emptyDraft(): VendorRequest {
  return {
    name: '',
    nameKana: null,
    category: null,
    phone: null,
    email: null,
    website: null,
    postalCode: null,
    address: null,
    representative: null,
    contactPerson: null,
    licenseNumber: null,
    licenseExpiry: null,
    note: null,
    isActive: true,
  }
}

function openCreate() {
  editingId.value = null
  draft.value = emptyDraft()
  showDialog.value = true
}

function openEdit(vendor: VendorResponse) {
  editingId.value = vendor.id
  draft.value = {
    name: vendor.name,
    nameKana: vendor.nameKana,
    category: vendor.category,
    phone: vendor.phone,
    email: vendor.email,
    website: vendor.website,
    postalCode: vendor.postalCode,
    address: vendor.address,
    representative: vendor.representative,
    contactPerson: vendor.contactPerson,
    licenseNumber: vendor.licenseNumber,
    licenseExpiry: vendor.licenseExpiry,
    note: vendor.note,
    isActive: vendor.isActive,
    version: vendor.version,
  }
  showDialog.value = true
}

async function save() {
  if (!draft.value.name.trim()) {
    showError(t('validation.required'))
    return
  }
  saving.value = true
  try {
    if (editingId.value === null) {
      await api.value.create(draft.value)
    } else {
      await api.value.update(editingId.value, draft.value)
    }
    showSuccess(t('property.saved'))
    showDialog.value = false
    await load()
  } catch (err) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      showError(t('property.errors.versionConflict'))
      await load()
    } else {
      showError(t('property.errors.saveFailed'))
    }
  } finally {
    saving.value = false
  }
}

async function remove(vendor: VendorResponse) {
  if (!confirm(t('property.vendor.deleteConfirm'))) return
  try {
    await api.value.remove(vendor.id)
    showSuccess(t('property.deleted'))
    await load()
  } catch {
    showError(t('property.errors.deleteFailed'))
  }
}

function categoryLabel(c: VendorCategory | null): string {
  return c ? t(`property.vendor.category.${c}`) : '-'
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <PageHeader :title="t('property.vendor.title')" />
        <p class="text-sm text-surface-500 dark:text-surface-400">
          {{ t('property.vendor.subtitle') }}
        </p>
      </div>
      <Button
        icon="pi pi-plus"
        :label="t('property.vendor.newVendor')"
        severity="primary"
        :disabled="scopeId === '0'"
        data-testid="vendor-new-btn"
        @click="openCreate"
      />
    </header>

    <p
      v-if="scopeId === '0'"
      class="rounded-md border border-dashed border-yellow-300 bg-yellow-50 p-4 text-sm text-yellow-700 dark:border-yellow-800 dark:bg-yellow-950 dark:text-yellow-200"
    >
      ?scope=teams&scopeId=N
    </p>

    <section v-else class="space-y-3">
      <div class="flex flex-wrap gap-2">
        <span class="p-input-icon-left">
          <i class="pi pi-search" />
          <InputText v-model="search" :placeholder="t('property.vendor.search')" />
        </span>
        <Dropdown
          v-model="filterCategory"
          :options="categoryOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('property.vendor.category.title')"
          show-clear
        />
      </div>

      <DataTable
        :value="items"
        :loading="loading"
        striped-rows
        size="small"
        data-testid="vendor-table"
      >
        <Column :header="t('property.vendor.fields.name')">
          <template #body="{ data }">
            <div class="font-medium">{{ (data as VendorResponse).name }}</div>
            <div
              v-if="(data as VendorResponse).nameKana"
              class="text-xs text-surface-500"
            >
              {{ (data as VendorResponse).nameKana }}
            </div>
          </template>
        </Column>
        <Column :header="t('property.vendor.category.title')">
          <template #body="{ data }">
            {{ categoryLabel((data as VendorResponse).category) }}
          </template>
        </Column>
        <Column :header="t('property.vendor.fields.phone')" field="phone" />
        <Column :header="t('property.vendor.fields.email')" field="email" />
        <Column :header="t('property.vendor.isActive')">
          <template #body="{ data }">
            <span
              v-if="(data as VendorResponse).isActive"
              class="rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-700 dark:bg-green-900 dark:text-green-300"
            >
              {{ t('property.vendor.isActive') }}
            </span>
            <span
              v-else
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-800"
            >
              {{ t('property.vendor.isInactive') }}
            </span>
          </template>
        </Column>
        <Column header="">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button
                icon="pi pi-pencil"
                severity="secondary"
                text
                rounded
                size="small"
                :aria-label="t('property.actions.edit')"
                @click="openEdit(data as VendorResponse)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                rounded
                size="small"
                :aria-label="t('property.actions.delete')"
                @click="remove(data as VendorResponse)"
              />
            </div>
          </template>
        </Column>
        <template #empty>
          <p class="p-4 text-center text-sm text-surface-500">
            {{ t('property.vendor.noVendors') }}
          </p>
        </template>
      </DataTable>

      <Paginator
        v-if="totalRecords > size"
        v-model:first="page"
        :rows="size"
        :total-records="totalRecords"
        :rows-per-page-options="[10, 20, 50]"
        @update:rows="(v: number) => (size = v)"
      />
    </section>

    <Dialog
      v-model:visible="showDialog"
      :header="editingId === null ? t('property.vendor.newVendor') : t('property.vendor.editVendor')"
      modal
      :style="{ width: '42rem' }"
      :breakpoints="{ '768px': '90vw' }"
    >
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.name') }}</label>
          <InputText v-model="draft.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.nameKana') }}</label>
          <InputText v-model="draft.nameKana" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.category.title') }}</label>
          <Dropdown
            v-model="draft.category"
            :options="categoryOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            show-clear
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.phone') }}</label>
          <InputText v-model="draft.phone" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.email') }}</label>
          <InputText v-model="draft.email" class="w-full" type="email" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.website') }}</label>
          <InputText v-model="draft.website" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.postalCode') }}</label>
          <InputText v-model="draft.postalCode" class="w-full" />
        </div>
        <div class="md:col-span-2">
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.address') }}</label>
          <InputText v-model="draft.address" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.representative') }}</label>
          <InputText v-model="draft.representative" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.contactPerson') }}</label>
          <InputText v-model="draft.contactPerson" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.licenseNumber') }}</label>
          <InputText v-model="draft.licenseNumber" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.licenseExpiry') }}</label>
          <InputText v-model="draft.licenseExpiry" type="date" class="w-full" />
        </div>
        <div class="md:col-span-2">
          <label class="mb-1 block text-sm font-medium">{{ t('property.vendor.fields.note') }}</label>
          <Textarea v-model="draft.note" rows="2" class="w-full" />
        </div>
        <div class="md:col-span-2">
          <label class="flex items-center gap-2 text-sm">
            <InputSwitch v-model="draft.isActive" />
            {{ t('property.vendor.isActive') }}
          </label>
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('property.actions.cancel')"
          severity="secondary"
          text
          @click="showDialog = false"
        />
        <Button
          :label="t('property.actions.save')"
          :loading="saving"
          severity="primary"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
