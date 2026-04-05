<script setup lang="ts">
import type { SkillCategoryResponse } from '~/types/skill'

const props = defineProps<{
  teamId: number
}>()

const {
  getSkillCategories,
  createSkillCategory,
  updateSkillCategory,
  deleteSkillCategory,
} = useSkillApi()
const notification = useNotification()

const categories = ref<SkillCategoryResponse[]>([])
const loading = ref(false)
const showDialog = ref(false)
const saving = ref(false)
const editingCategory = ref<SkillCategoryResponse | null>(null)

const form = ref({
  name: '',
  description: '',
  icon: '',
  sortOrder: 0,
  isActive: true,
})

async function loadCategories() {
  loading.value = true
  try {
    const res = await getSkillCategories(props.teamId)
    categories.value = res.data
  } catch {
    notification.error('カテゴリの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingCategory.value = null
  form.value = { name: '', description: '', icon: '', sortOrder: 0, isActive: true }
  showDialog.value = true
}

function openEdit(cat: SkillCategoryResponse) {
  editingCategory.value = cat
  form.value = {
    name: cat.name,
    description: cat.description || '',
    icon: cat.icon || '',
    sortOrder: cat.sortOrder,
    isActive: cat.isActive,
  }
  showDialog.value = true
}

async function submit() {
  if (!form.value.name.trim()) return
  saving.value = true
  try {
    if (editingCategory.value) {
      await updateSkillCategory(props.teamId, editingCategory.value.id, {
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
        icon: form.value.icon.trim() || undefined,
        sortOrder: form.value.sortOrder,
        isActive: form.value.isActive,
      })
      notification.success('カテゴリを更新しました')
    } else {
      await createSkillCategory(props.teamId, {
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
        icon: form.value.icon.trim() || undefined,
        sortOrder: form.value.sortOrder,
      })
      notification.success('カテゴリを作成しました')
    }
    showDialog.value = false
    await loadCategories()
  } catch {
    notification.error('カテゴリの保存に失敗しました')
  } finally {
    saving.value = false
  }
}

async function handleDelete(cat: SkillCategoryResponse) {
  try {
    await deleteSkillCategory(props.teamId, cat.id)
    notification.success('カテゴリを削除しました')
    await loadCategories()
  } catch {
    notification.error('カテゴリの削除に失敗しました')
  }
}

onMounted(loadCategories)
defineExpose({ refresh: loadCategories })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">カテゴリ管理</h2>
      <Button label="カテゴリ追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else-if="categories.length === 0" class="py-12 text-center">
      <i class="pi pi-tags mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">カテゴリがありません</p>
    </div>

    <div v-else class="space-y-2">
      <div
        v-for="cat in categories"
        :key="cat.id"
        class="flex items-center gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-surface-100 dark:bg-surface-700">
          <i :class="cat.icon || 'pi pi-tag'" class="text-lg text-surface-400" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <h3 class="text-sm font-semibold">{{ cat.name }}</h3>
            <span
              v-if="!cat.isActive"
              class="rounded bg-surface-200 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-600"
            >
              無効
            </span>
          </div>
          <p v-if="cat.description" class="text-xs text-surface-400">{{ cat.description }}</p>
          <p class="text-xs text-surface-400">表示順: {{ cat.sortOrder }}</p>
        </div>
        <div class="flex shrink-0 gap-1">
          <Button
            icon="pi pi-pencil"
            size="small"
            text
            @click="openEdit(cat)"
          />
          <Button
            icon="pi pi-trash"
            size="small"
            text
            severity="danger"
            @click="handleDelete(cat)"
          />
        </div>
      </div>
    </div>

    <Dialog
      v-model:visible="showDialog"
      :header="editingCategory ? 'カテゴリ編集' : 'カテゴリ追加'"
      modal
      :style="{ width: '450px' }"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            カテゴリ名 <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.name"
            class="w-full"
            placeholder="例: 運転免許"
            autofocus
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <InputText
            v-model="form.description"
            class="w-full"
            placeholder="カテゴリの説明"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">アイコン</label>
          <InputText
            v-model="form.icon"
            class="w-full"
            placeholder="例: pi pi-car"
          />
          <p class="mt-1 text-xs text-surface-400">PrimeIcons のクラス名を入力</p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">表示順</label>
          <InputNumber v-model="form.sortOrder" class="w-full" :min="0" />
        </div>

        <div v-if="editingCategory" class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isActive" />
          <label class="text-sm">有効</label>
        </div>
      </div>

      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button
          :label="editingCategory ? '更新' : '作成'"
          icon="pi pi-check"
          :loading="saving"
          :disabled="!form.name.trim()"
          @click="submit"
        />
      </template>
    </Dialog>
  </div>
</template>
