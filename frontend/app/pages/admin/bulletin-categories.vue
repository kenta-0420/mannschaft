<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const { getCategories, createCategory, updateCategory, deleteCategory } = useBulletinApi()

interface CategoryItem {
  id: number
  name: string
  description: string | null
  color: string | null
  orderIndex: number
  isDefault: boolean
}

const categories = ref<CategoryItem[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<CategoryItem | null>(null)
const form = ref({ name: '', description: '', color: '#6366F1' })
const saving = ref(false)

const COLOR_PRESETS = [
  '#6366F1', '#3B82F6', '#10B981', '#F59E0B',
  '#EF4444', '#8B5CF6', '#EC4899', '#64748B',
]

async function load() {
  loading.value = true
  try {
    const res = await getCategories(scopeType.value, scopeId.value)
    categories.value = (res as unknown as { data: CategoryItem[] }).data
  } catch {
    showError('カテゴリの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { name: '', description: '', color: '#6366F1' }
  showDialog.value = true
}

function openEdit(item: CategoryItem) {
  editingItem.value = item
  form.value = { name: item.name, description: item.description ?? '', color: item.color ?? '#6366F1' }
  showDialog.value = true
}

async function save() {
  if (!form.value.name) return
  saving.value = true
  try {
    if (editingItem.value) {
      await updateCategory(editingItem.value.id, form.value)
      success('カテゴリを更新しました')
    } else {
      await createCategory({ ...form.value, scopeType: scopeType.value, scopeId: scopeId.value })
      success('カテゴリを作成しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

async function remove(item: CategoryItem) {
  if (!confirm(`「${item.name}」を削除しますか？`)) return
  try {
    await deleteCategory(item.id)
    success('カテゴリを削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">掲示板カテゴリ管理</h1>
      <Button label="カテゴリを追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="categories" striped-rows data-key="id">
      <template #empty>
        <div class="py-12 text-center">
          <i class="pi pi-list mb-3 text-4xl text-surface-300" />
          <p class="text-surface-400">カテゴリがありません</p>
        </div>
      </template>
      <Column header="色" style="width: 60px">
        <template #body="{ data }">
          <span
            class="inline-block h-4 w-4 rounded-full"
            :style="{ backgroundColor: data.color ?? '#6366F1' }"
          />
        </template>
      </Column>
      <Column field="name" header="カテゴリ名" />
      <Column field="description" header="説明">
        <template #body="{ data }">
          <span class="text-sm text-surface-500">{{ data.description ?? '-' }}</span>
        </template>
      </Column>
      <Column header="デフォルト" style="width: 100px">
        <template #body="{ data }">
          <i :class="data.isDefault ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-300'" />
        </template>
      </Column>
      <Column header="操作" style="width: 120px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button icon="pi pi-pencil" size="small" text severity="info" @click="openEdit(data)" />
            <Button
              icon="pi pi-trash"
              size="small"
              text
              severity="danger"
              :disabled="data.isDefault"
              @click="remove(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      :header="editingItem ? 'カテゴリ編集' : 'カテゴリ追加'"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">カテゴリ名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" class="w-full" placeholder="例: お知らせ" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="form.description" class="w-full" rows="2" />
        </div>
        <div>
          <label class="mb-2 block text-sm font-medium">カラー</label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="color in COLOR_PRESETS"
              :key="color"
              type="button"
              class="h-8 w-8 rounded-full border-2 transition-transform hover:scale-110"
              :class="form.color === color ? 'border-surface-900' : 'border-transparent'"
              :style="{ backgroundColor: color }"
              @click="form.color = color"
            />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button label="保存" :loading="saving" :disabled="!form.name" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
