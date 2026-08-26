<script setup lang="ts">
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputNumber from 'primevue/inputnumber'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'

import type { NavFeatureAdminItem, NavFeatureCreateRequest, NavFeatureUpdateRequest } from '~/types/nav'

definePageMeta({ middleware: 'auth' })

const { listNavFeatures, createNavFeature, updateNavFeature, deleteNavFeature } = useNavSettingsApi()
const { showSuccess, showError } = useNotification()

const features = ref<NavFeatureAdminItem[]>([])
const loading = ref(false)

// 新規追加ダイアログ
const showCreateDialog = ref(false)
const createForm = ref<NavFeatureCreateRequest>({
  key: '',
  labelKey: '',
  icon: 'pi pi-circle',
  path: '/',
  fixed: false,
  enabled: true,
  subscriptionRequired: false,
  sortOrder: 50,
  mobileVisible: true,
})

// 編集ダイアログ
const showEditDialog = ref(false)
const editingKey = ref('')
const editForm = ref<NavFeatureUpdateRequest>({
  labelKey: '',
  icon: '',
  path: '',
  fixed: false,
  enabled: true,
  subscriptionRequired: false,
  sortOrder: 0,
  mobileVisible: true,
})

async function load() {
  loading.value = true
  try {
    features.value = await listNavFeatures()
  } catch {
    showError('取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function toggleEnabled(item: NavFeatureAdminItem) {
  try {
    await updateNavFeature(item.key, {
      labelKey: item.labelKey,
      icon: item.icon,
      path: item.path,
      fixed: item.fixed,
      enabled: !item.enabled,
      subscriptionRequired: item.subscriptionRequired,
      sortOrder: item.sortOrder,
      mobileVisible: item.mobileVisible,
    })
    await load()
    showSuccess((!item.enabled ? '有効' : '無効') + 'にしました')
  } catch {
    showError('更新に失敗しました')
  }
}

function openEdit(item: NavFeatureAdminItem) {
  editingKey.value = item.key
  editForm.value = {
    labelKey: item.labelKey,
    icon: item.icon,
    path: item.path,
    fixed: item.fixed,
    enabled: item.enabled,
    subscriptionRequired: item.subscriptionRequired,
    sortOrder: item.sortOrder,
    mobileVisible: item.mobileVisible,
  }
  showEditDialog.value = true
}

async function submitEdit() {
  try {
    await updateNavFeature(editingKey.value, editForm.value)
    showEditDialog.value = false
    await load()
    showSuccess('更新しました')
  } catch {
    showError('更新に失敗しました')
  }
}

async function submitCreate() {
  try {
    await createNavFeature(createForm.value)
    showCreateDialog.value = false
    createForm.value = {
      key: '',
      labelKey: '',
      icon: 'pi pi-circle',
      path: '/',
      fixed: false,
      enabled: true,
      subscriptionRequired: false,
      sortOrder: 50,
      mobileVisible: true,
    }
    await load()
    showSuccess('追加しました')
  } catch {
    showError('追加に失敗しました')
  }
}

async function remove(item: NavFeatureAdminItem) {
  if (!confirm(`「${item.key}」を削除しますか？`)) return
  try {
    await deleteNavFeature(item.key)
    await load()
    showSuccess('削除しました')
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="ナビゲーション機能管理" />
      <Button label="項目を追加" icon="pi pi-plus" size="small" @click="showCreateDialog = true" />
    </div>

    <PageLoading v-if="loading" size="40px" />
    <div v-else>
      <DataTable :value="features" striped-rows class="text-sm">
        <Column field="key" header="キー" />
        <Column field="labelKey" header="i18nキー" />
        <Column field="icon" header="アイコン">
          <template #body="{ data }">
            <i :class="data.icon" />
          </template>
        </Column>
        <Column field="path" header="パス" />
        <Column field="sortOrder" header="順序" />
        <Column header="固定">
          <template #body="{ data }">
            <i v-if="data.fixed" class="pi pi-lock text-surface-400" />
          </template>
        </Column>
        <Column header="課金">
          <template #body="{ data }">
            <Tag v-if="data.subscriptionRequired" value="課金" severity="warn" rounded />
          </template>
        </Column>
        <Column header="有効">
          <template #body="{ data }">
            <ToggleSwitch :model-value="data.enabled" @update:model-value="() => toggleEnabled(data)" />
          </template>
        </Column>
        <Column header="操作">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button icon="pi pi-pencil" text rounded size="small" @click="openEdit(data)" />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                :disabled="data.fixed"
                @click="remove(data)"
              />
            </div>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- 新規追加ダイアログ -->
    <Dialog v-model:visible="showCreateDialog" header="ナビ項目を追加" :style="{ width: '480px' }" modal>
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-xs font-medium">キー（^[a-z0-9-]+$）</label>
          <InputText v-model="createForm.key" class="w-full" placeholder="例: reservation-management" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">i18nキー</label>
          <InputText v-model="createForm.labelKey" class="w-full" placeholder="例: nav.reservationManagement" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">アイコン</label>
          <InputText v-model="createForm.icon" class="w-full" placeholder="例: pi pi-bookmark" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">パス</label>
          <InputText v-model="createForm.path" class="w-full" placeholder="例: /reservations" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">表示順</label>
          <InputNumber v-model="createForm.sortOrder" class="w-full" :min="0" :max="9999" />
        </div>
        <div class="flex gap-4">
          <div class="flex items-center gap-2">
            <ToggleSwitch v-model="createForm.enabled" />
            <span class="text-sm">有効</span>
          </div>
          <div class="flex items-center gap-2">
            <ToggleSwitch v-model="createForm.subscriptionRequired" />
            <span class="text-sm">課金必須</span>
          </div>
          <div class="flex items-center gap-2">
            <ToggleSwitch v-model="createForm.mobileVisible" />
            <span class="text-sm">モバイル表示</span>
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showCreateDialog = false" />
        <Button label="追加" icon="pi pi-check" @click="submitCreate" />
      </template>
    </Dialog>

    <!-- 編集ダイアログ -->
    <Dialog v-model:visible="showEditDialog" :header="`編集: ${editingKey}`" :style="{ width: '480px' }" modal>
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-xs font-medium">i18nキー</label>
          <InputText v-model="editForm.labelKey" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">アイコン</label>
          <InputText v-model="editForm.icon" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">パス</label>
          <InputText v-model="editForm.path" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium">表示順</label>
          <InputNumber v-model="editForm.sortOrder" class="w-full" :min="0" :max="9999" />
        </div>
        <div class="flex gap-4">
          <div class="flex items-center gap-2">
            <ToggleSwitch v-model="editForm.enabled" />
            <span class="text-sm">有効</span>
          </div>
          <div class="flex items-center gap-2">
            <ToggleSwitch v-model="editForm.subscriptionRequired" />
            <span class="text-sm">課金必須</span>
          </div>
          <div class="flex items-center gap-2">
            <ToggleSwitch v-model="editForm.mobileVisible" />
            <span class="text-sm">モバイル表示</span>
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showEditDialog = false" />
        <Button label="更新" icon="pi pi-check" @click="submitEdit" />
      </template>
    </Dialog>
  </div>
</template>
