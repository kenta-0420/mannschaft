<script setup lang="ts">
/**
 * システム管理者向け: 村カテゴリ管理ページ
 *
 * SYSTEM_ADMIN が村カテゴリのマスタデータを管理する。
 * - ツリー構造をフラットリスト＋インデントで表示
 * - カテゴリの追加・編集・削除（論理削除）
 *
 * API:
 *   GET    /api/v1/system-admin/village-categories
 *   POST   /api/v1/system-admin/village-categories
 *   PUT    /api/v1/system-admin/village-categories/{id}
 *   DELETE /api/v1/system-admin/village-categories/{id}
 */
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputNumber from 'primevue/inputnumber'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Tag from 'primevue/tag'

import type { VillageCategoryRequest, VillageCategoryResponse } from '~/types/villageCategory'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const categoryApi = useVillageCategoryApi()
const notification = useNotification()

// SYSTEM_ADMIN 権限チェック
const isAllowed = computed(() => authStore.isSystemAdmin)

// =============================================================================
// カテゴリデータ
// =============================================================================

const rawCategories = ref<VillageCategoryResponse[]>([])
const loading = ref(false)

/** ツリー構造をフラットリストに変換（level付き） */
interface FlatCategory extends VillageCategoryResponse {
  level: number
}

const flatCategories = computed<FlatCategory[]>(() => {
  const result: FlatCategory[] = []

  function flatten(items: VillageCategoryResponse[], level: number) {
    for (const item of items) {
      result.push({ ...item, level })
      if (item.children && item.children.length > 0) {
        flatten(item.children, level + 1)
      }
    }
  }

  flatten(rawCategories.value, 0)
  return result
})

/** 親カテゴリ選択肢（フォーム用 — 2階層まで）: なし（ルート）+ ルート・中分類カテゴリ */
const parentOptions = computed<Array<{ id: string | null; name: string }>>(() => {
  const opts: Array<{ id: string | null; name: string }> = [
    { id: null, name: t('village.categoryManagement.parentNone') },
  ]
  for (const cat of flatCategories.value) {
    // 小分類（level 2）を親にする3段目は許可しない（最大3階層）
    if (cat.level < 2) {
      opts.push({ id: cat.id, name: '  '.repeat(cat.level) + cat.name })
    }
  }
  return opts
})

async function load() {
  loading.value = true
  try {
    rawCategories.value = await categoryApi.fetchAdminCategories()
  } catch (err) {
    console.error('village-categories.vue: load failed', err)
    notification.error(t('village.categoryManagement.title') + ' — 取得に失敗しました')
    rawCategories.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

// =============================================================================
// 追加・編集 Dialog
// =============================================================================

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const saving = ref(false)

/** フォーム状態 */
const formName = ref('')
const formParentId = ref<string | null>(null)
const formDisplayOrder = ref<number>(10)

const CATEGORY_NAME_MAX = 64

const nameError = computed<string | null>(() => {
  const v = formName.value.trim()
  if (!v) return t('village.categoryManagement.nameLabel') + 'は必須です'
  if (v.length > CATEGORY_NAME_MAX) return `${CATEGORY_NAME_MAX}文字以内で入力してください`
  return null
})

const canSave = computed(() => !nameError.value && !saving.value)

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  formName.value = ''
  formParentId.value = null
  formDisplayOrder.value = 10
  dialogOpen.value = true
}

function openEdit(cat: FlatCategory) {
  dialogMode.value = 'edit'
  editingId.value = cat.id
  formName.value = cat.name
  formParentId.value = cat.parentId
  formDisplayOrder.value = cat.displayOrder
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
  editingId.value = null
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const req: VillageCategoryRequest = {
      name: formName.value.trim(),
      parentId: formParentId.value,
      displayOrder: formDisplayOrder.value ?? null,
    }

    if (dialogMode.value === 'create') {
      await categoryApi.createCategory(req)
      notification.success(t('village.categoryManagement.addCategory') + 'しました')
    } else if (editingId.value) {
      await categoryApi.updateCategory(editingId.value, req)
      notification.success(t('village.categoryManagement.editCategory') + 'しました')
    }

    closeDialog()
    await load()
  } catch (err) {
    console.error('village-categories.vue: save failed', err)
    notification.error('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

// =============================================================================
// 削除
// =============================================================================

const deletingId = ref<string | null>(null)

async function deleteCategory(cat: FlatCategory) {
  if (!confirm(t('village.categoryManagement.deleteConfirm'))) return
  deletingId.value = cat.id
  try {
    await categoryApi.deleteCategory(cat.id)
    notification.success('削除しました')
    await load()
  } catch (err) {
    console.error('village-categories.vue: delete failed', err)
    notification.error('削除に失敗しました')
  } finally {
    deletingId.value = null
  }
}

// =============================================================================
// 表示ヘルパ
// =============================================================================

function levelLabel(level: number): string {
  switch (level) {
    case 0:
      return t('village.categoryManagement.levelRoot')
    case 1:
      return t('village.categoryManagement.level2')
    case 2:
      return t('village.categoryManagement.level3')
    default:
      return String(level + 1) + '階層'
  }
}

function levelSeverity(level: number): 'success' | 'info' | 'secondary' {
  switch (level) {
    case 0:
      return 'success'
    case 1:
      return 'info'
    default:
      return 'secondary'
  }
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <!-- 権限チェック -->
    <div
      v-if="!isAllowed"
      class="flex flex-col items-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400"
    >
      <i class="pi pi-lock text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('village.creationRequest.noPermission') }}</p>
    </div>

    <template v-else>
      <!-- ヘッダー -->
      <header class="flex items-center justify-between">
        <div>
          <span
            class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
          >
            SYSTEM ADMIN
          </span>
          <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
            {{ t('village.categoryManagement.title') }}
          </h1>
        </div>
        <div class="flex items-center gap-2">
          <Button
            v-tooltip.left="'再読み込み'"
            icon="pi pi-refresh"
            text
            rounded
            :loading="loading"
            @click="load"
          />
          <Button
            :label="t('village.categoryManagement.addCategory')"
            icon="pi pi-plus"
            @click="openCreate"
          />
        </div>
      </header>

      <!-- ローディング -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
      </div>

      <!-- カテゴリテーブル -->
      <DataTable
        v-else
        :value="flatCategories"
        data-key="id"
        striped-rows
        class="text-sm"
      >
        <template #empty>
          <div
            class="flex flex-col items-center justify-center gap-3 py-12 text-surface-400"
          >
            <i class="pi pi-inbox text-4xl" aria-hidden="true" />
            <p class="text-sm">カテゴリはまだ登録されていません</p>
          </div>
        </template>

        <!-- カテゴリ名（インデント表示） -->
        <Column
          :header="t('village.categoryManagement.nameLabel')"
          style="min-width: 16rem"
        >
          <template #body="{ data: row }: { data: FlatCategory }">
            <div
              class="flex items-center gap-1"
              :style="{ paddingLeft: `${row.level * 24}px` }"
            >
              <span v-if="row.level > 0" class="mr-1 text-surface-400">└</span>
              <span class="font-medium text-surface-700 dark:text-surface-200">{{ row.name }}</span>
            </div>
          </template>
        </Column>

        <!-- 階層 -->
        <Column
          :header="'階層'"
          style="width: 8rem"
        >
          <template #body="{ data: row }: { data: FlatCategory }">
            <Tag :value="levelLabel(row.level)" :severity="levelSeverity(row.level)" />
          </template>
        </Column>

        <!-- 表示順 -->
        <Column
          :header="t('village.categoryManagement.orderLabel')"
          field="displayOrder"
          style="width: 7rem"
        />

        <!-- 操作 -->
        <Column :header="'操作'" style="width: 12rem">
          <template #body="{ data: row }: { data: FlatCategory }">
            <div class="flex items-center gap-1">
              <Button
                :label="'編集'"
                icon="pi pi-pencil"
                size="small"
                severity="secondary"
                @click="openEdit(row)"
              />
              <Button
                :label="'削除'"
                icon="pi pi-trash"
                size="small"
                severity="danger"
                text
                :loading="deletingId === row.id"
                @click="deleteCategory(row)"
              />
            </div>
          </template>
        </Column>
      </DataTable>
    </template>

    <!-- 追加・編集 Dialog -->
    <Dialog
      v-model:visible="dialogOpen"
      modal
      :header="dialogMode === 'create' ? t('village.categoryManagement.addCategory') : t('village.categoryManagement.editCategory')"
      :style="{ width: '32rem' }"
      :draggable="false"
      @hide="closeDialog"
    >
      <div class="flex flex-col gap-4">
        <!-- カテゴリ名 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('village.categoryManagement.nameLabel') }}
            <span class="text-red-600">*</span>
          </label>
          <InputText
            v-model="formName"
            :maxlength="CATEGORY_NAME_MAX"
            :invalid="!!nameError && formName.length > 0"
            class="w-full"
          />
          <p v-if="nameError && formName.length > 0" class="mt-1 text-xs text-red-600">
            {{ nameError }}
          </p>
        </div>

        <!-- 親カテゴリ -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('village.categoryManagement.parentLabel') }}
          </label>
          <Select
            v-model="formParentId"
            :options="parentOptions"
            option-label="name"
            option-value="id"
            class="w-full"
          />
        </div>

        <!-- 表示順 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('village.categoryManagement.orderLabel') }}
          </label>
          <InputNumber
            v-model="formDisplayOrder"
            :min="0"
            :max="9999"
            class="w-full"
          />
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          @click="closeDialog"
        />
        <Button
          :label="t('village.action.save')"
          :disabled="!canSave"
          :loading="saving"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
