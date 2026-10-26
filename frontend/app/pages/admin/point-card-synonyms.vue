<script setup lang="ts">
/**
 * F18 Phase 4 第三陣 S3 — SystemAdmin 専用 同義語管理ページ。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §7.6
 *
 * <p>運営マスタの fuzzy match 用同義語辞書を CRUD する。
 * 組織 ADMIN は触れない（このページは SystemAdmin 専用）。</p>
 */
import type { PointCardProvider } from '~/types/pointCard'
import type { SynonymItem } from '~/composables/useAdminWalletApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { success, error: showError } = useNotification()
const { formatDateTime } = useDatetime()
const adminWalletApi = useAdminWalletApi()
const walletApi = useWalletApi()

const synonyms = ref<SynonymItem[]>([])
const providers = ref<PointCardProvider[]>([])
const loading = ref(true)
const filterProviderId = ref<string | null>(null)

const showDialog = ref(false)
const editingItem = ref<SynonymItem | null>(null)
const saving = ref(false)
const form = ref<{ providerId: string | null; synonymDisplay: string; memo: string }>({
  providerId: null,
  synonymDisplay: '',
  memo: '',
})

async function loadProviders() {
  try {
    providers.value = await walletApi.listProviders()
  } catch {
    showError(t('wallet.synonym_admin.error_load_providers'))
  }
}

async function loadSynonyms() {
  loading.value = true
  try {
    const res = await adminWalletApi.listSynonyms(filterProviderId.value ?? undefined)
    synonyms.value = res.data
  } catch {
    showError(t('wallet.synonym_admin.error_load'))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { providerId: null, synonymDisplay: '', memo: '' }
  showDialog.value = true
}

function openEdit(item: SynonymItem) {
  editingItem.value = item
  form.value = {
    providerId: item.providerId,
    synonymDisplay: item.synonymDisplay,
    memo: item.memo ?? '',
  }
  showDialog.value = true
}

const canSave = computed(() => {
  if (!form.value.synonymDisplay.trim()) return false
  if (!editingItem.value && !form.value.providerId) return false
  return true
})

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    if (editingItem.value) {
      await adminWalletApi.updateSynonym(editingItem.value.id, {
        synonymDisplay: form.value.synonymDisplay.trim(),
        memo: form.value.memo.trim() || null,
      })
      success(t('wallet.synonym_admin.saved'))
    } else {
      await adminWalletApi.createSynonym({
        providerId: form.value.providerId as string,
        synonymDisplay: form.value.synonymDisplay.trim(),
        memo: form.value.memo.trim() || null,
      })
      success(t('wallet.synonym_admin.saved'))
    }
    showDialog.value = false
    await loadSynonyms()
  } catch (err) {
    const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'POINT_CARD_021') {
      showError(t('wallet.synonym_admin.error_duplicate'))
    } else if (code === 'POINT_CARD_007') {
      showError(t('wallet.synonym_admin.error_provider_not_found'))
    } else {
      showError(t('wallet.synonym_admin.error_save'))
    }
  } finally {
    saving.value = false
  }
}

async function remove(item: SynonymItem) {
  if (!confirm(t('wallet.synonym_admin.delete_confirm'))) return
  try {
    await adminWalletApi.deleteSynonym(item.id)
    success(t('wallet.synonym_admin.deleted'))
    await loadSynonyms()
  } catch {
    showError(t('wallet.synonym_admin.error_delete'))
  }
}

watch(filterProviderId, () => {
  void loadSynonyms()
})

onMounted(async () => {
  await Promise.all([loadProviders(), loadSynonyms()])
})

const providerOptions = computed(() =>
  providers.value.map((p) => ({ label: p.displayName, value: p.id })),
)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="t('wallet.synonym_admin.page_title')" />
      <Button :label="t('wallet.synonym_admin.add')" icon="pi pi-plus" @click="openCreate" />
    </div>

    <div class="mb-4 flex items-center gap-3">
      <label class="text-sm font-medium">{{ t('wallet.synonym_admin.provider_filter') }}</label>
      <Select
        v-model="filterProviderId"
        :options="providerOptions"
        option-label="label"
        option-value="value"
        :placeholder="t('wallet.synonym_admin.all_providers')"
        show-clear
        class="w-64"
      />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="synonyms" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-list" :message="t('wallet.synonym_admin.empty')" />
      </template>
      <Column :header="t('wallet.synonym_admin.col_provider')" style="min-width: 160px">
        <template #body="{ data }">
          <span>{{ data.providerDisplayName ?? '-' }}</span>
        </template>
      </Column>
      <Column :header="t('wallet.synonym_admin.col_synonym')" style="min-width: 200px">
        <template #body="{ data }">
          <span class="font-medium">{{ data.synonymDisplay }}</span>
        </template>
      </Column>
      <Column :header="t('wallet.synonym_admin.col_normalized')" style="min-width: 200px">
        <template #body="{ data }">
          <code class="text-xs text-surface-500">{{ data.synonymNormalized }}</code>
        </template>
      </Column>
      <Column :header="t('wallet.synonym_admin.col_memo')" style="min-width: 160px">
        <template #body="{ data }">
          <span class="text-sm text-surface-500">{{ data.memo ?? '-' }}</span>
        </template>
      </Column>
      <Column :header="t('wallet.synonym_admin.col_created')" style="width: 180px">
        <template #body="{ data }">
          <span class="text-sm">{{ formatDateTime(data.createdAt) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 120px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              icon="pi pi-pencil"
              size="small"
              text
              severity="info"
              @click="openEdit(data)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              text
              severity="danger"
              @click="remove(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      :header="
        editingItem
          ? t('wallet.synonym_admin.modal_title_edit')
          : t('wallet.synonym_admin.modal_title_add')
      "
      :style="{ width: '480px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div v-if="!editingItem">
          <label class="mb-1 block text-sm font-medium">
            {{ t('wallet.synonym_admin.label_provider') }} <span class="text-red-500">*</span>
          </label>
          <Select
            v-model="form.providerId"
            :options="providerOptions"
            option-label="label"
            option-value="value"
            :placeholder="t('wallet.synonym_admin.label_provider')"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('wallet.synonym_admin.label_synonym') }} <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.synonymDisplay"
            class="w-full"
            :maxlength="100"
            :placeholder="t('wallet.synonym_admin.label_synonym_placeholder')"
          />
          <p class="mt-1 text-xs text-surface-500">
            {{ t('wallet.synonym_admin.label_synonym_hint') }}
          </p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('wallet.synonym_admin.label_memo') }}
          </label>
          <InputText v-model="form.memo" class="w-full" :maxlength="200" />
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('wallet.synonym_admin.cancel')"
          severity="secondary"
          text
          @click="showDialog = false"
        />
        <Button
          :label="t('wallet.synonym_admin.save')"
          :loading="saving"
          :disabled="!canSave"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
