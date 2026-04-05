<script setup lang="ts">
import type { IncidentCategoryResponse } from '~/types/incident'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  scopeType: string
  scopeId: number
}>()

const { listCategories, createCategory, updateCategory, deleteCategory } = useIncidentApi()
const { success: showSuccess, error: showError } = useNotification()

const categories = ref<IncidentCategoryResponse[]>([])
const loading = ref(false)
const showForm = ref(false)
const editTarget = ref<IncidentCategoryResponse | null>(null)
const submitting = ref(false)

// フォームフィールド
const formName = ref('')
const formDescription = ref('')
const formIcon = ref('')
const formColor = ref('')
const formSlaHours = ref<number | undefined>(undefined)
const formSortOrder = ref<number | undefined>(undefined)
const formIsActive = ref(true)

const isEdit = computed(() => !!editTarget.value)

async function loadCategories() {
  loading.value = true
  try {
    const res = await listCategories(props.scopeType, props.scopeId)
    categories.value = res.data
  } catch {
    showError('カテゴリの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editTarget.value = null
  formName.value = ''
  formDescription.value = ''
  formIcon.value = ''
  formColor.value = ''
  formSlaHours.value = undefined
  formSortOrder.value = undefined
  formIsActive.value = true
  showForm.value = true
}

function openEdit(cat: IncidentCategoryResponse) {
  editTarget.value = cat
  formName.value = cat.name
  formDescription.value = cat.description || ''
  formIcon.value = cat.icon || ''
  formColor.value = cat.color || ''
  formSlaHours.value = cat.slaHours ?? undefined
  formSortOrder.value = cat.sortOrder
  formIsActive.value = cat.isActive
  showForm.value = true
}

async function onSubmit() {
  if (!formName.value.trim() || submitting.value) return
  submitting.value = true
  try {
    if (isEdit.value && editTarget.value) {
      await updateCategory(editTarget.value.id, {
        name: formName.value.trim(),
        description: formDescription.value.trim() || undefined,
        icon: formIcon.value.trim() || undefined,
        color: formColor.value.trim() || undefined,
        slaHours: formSlaHours.value,
        isActive: formIsActive.value,
        sortOrder: formSortOrder.value,
      })
      showSuccess('カテゴリを更新しました')
    } else {
      await createCategory({
        scopeType: props.scopeType,
        scopeId: props.scopeId,
        name: formName.value.trim(),
        description: formDescription.value.trim() || undefined,
        icon: formIcon.value.trim() || undefined,
        color: formColor.value.trim() || undefined,
        slaHours: formSlaHours.value,
        sortOrder: formSortOrder.value,
      })
      showSuccess('カテゴリを作成しました')
    }
    showForm.value = false
    loadCategories()
  } catch {
    showError(isEdit.value ? '更新に失敗しました' : '作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

async function onDelete(cat: IncidentCategoryResponse) {
  try {
    await deleteCategory(cat.id)
    showSuccess('カテゴリを削除しました')
    loadCategories()
  } catch {
    showError('削除に失敗しました')
  }
}

watch(visible, (v) => {
  if (v) loadCategories()
})
</script>

<template>
  <Dialog v-model:visible="visible" header="カテゴリ管理" modal class="w-full max-w-2xl">
    <!-- カテゴリ一覧 -->
    <div v-if="!showForm">
      <div class="mb-4 flex items-center justify-between">
        <span class="text-sm text-surface-500">{{ categories.length }}件のカテゴリ</span>
        <Button label="新規作成" icon="pi pi-plus" size="small" @click="openCreate" />
      </div>

      <div v-if="loading" class="flex justify-center py-8">
        <ProgressSpinner style="width: 40px; height: 40px" />
      </div>

      <div v-else class="flex flex-col gap-2">
        <div
          v-for="cat in categories"
          :key="cat.id"
          class="flex items-center justify-between rounded-lg border border-surface-200 p-3 dark:border-surface-600"
        >
          <div class="flex items-center gap-3">
            <div
              class="flex h-8 w-8 items-center justify-center rounded-lg text-sm"
              :style="cat.color ? { backgroundColor: cat.color + '20', color: cat.color } : {}"
              :class="!cat.color ? 'bg-surface-100 text-surface-500' : ''"
            >
              <i :class="cat.icon || 'pi pi-tag'" />
            </div>
            <div>
              <div class="flex items-center gap-2">
                <span class="text-sm font-medium">{{ cat.name }}</span>
                <span
                  v-if="!cat.isActive"
                  class="rounded bg-surface-200 px-1 py-0.5 text-xs text-surface-500 dark:bg-surface-600"
                >
                  無効
                </span>
              </div>
              <div class="text-xs text-surface-400">
                <span v-if="cat.slaHours">SLA: {{ cat.slaHours }}時間</span>
                <span v-if="cat.description"> / {{ cat.description }}</span>
              </div>
            </div>
          </div>
          <div class="flex items-center gap-1">
            <Button icon="pi pi-pencil" text size="small" @click="openEdit(cat)" />
            <Button
              icon="pi pi-trash"
              text
              size="small"
              severity="danger"
              @click="onDelete(cat)"
            />
          </div>
        </div>
      </div>

      <div v-if="!loading && categories.length === 0" class="py-8 text-center text-sm text-surface-400">
        カテゴリがありません
      </div>
    </div>

    <!-- カテゴリフォーム -->
    <div v-else>
      <div class="mb-4">
        <Button
          icon="pi pi-arrow-left"
          label="一覧に戻る"
          text
          size="small"
          @click="showForm = false"
        />
      </div>
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">名前</label>
          <InputText v-model="formName" class="w-full" placeholder="カテゴリ名" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <InputText v-model="formDescription" class="w-full" placeholder="カテゴリの説明" />
        </div>
        <div class="flex gap-4">
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">アイコン (PrimeIcon)</label>
            <InputText v-model="formIcon" class="w-full" placeholder="pi pi-tag" />
          </div>
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">カラー</label>
            <InputText v-model="formColor" class="w-full" placeholder="#3B82F6" />
          </div>
        </div>
        <div class="flex gap-4">
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">SLA（時間）</label>
            <InputNumber v-model="formSlaHours" class="w-full" placeholder="24" :min="0" />
          </div>
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">表示順</label>
            <InputNumber v-model="formSortOrder" class="w-full" placeholder="0" :min="0" />
          </div>
        </div>
        <div v-if="isEdit" class="flex items-center gap-2">
          <ToggleSwitch v-model="formIsActive" />
          <label class="text-sm">有効</label>
        </div>
      </div>
      <div class="mt-4 flex justify-end gap-2">
        <Button label="キャンセル" text @click="showForm = false" />
        <Button
          :label="isEdit ? '更新' : '作成'"
          :loading="submitting"
          :disabled="!formName.trim()"
          @click="onSubmit"
        />
      </div>
    </div>
  </Dialog>
</template>
