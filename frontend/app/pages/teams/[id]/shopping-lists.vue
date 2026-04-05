<script setup lang="ts">
import type { ShoppingListResponse } from '~/types/shopping-list'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const shoppingApi = useShoppingListApi()
const { showError } = useNotification()

const lists = ref<ShoppingListResponse[]>([])
const selectedList = ref<ShoppingListResponse | null>(null)
const loading = ref(true)
const showListDialog = ref(false)
const listName = ref('')
const isTemplate = ref(false)
const editingList = ref<ShoppingListResponse | null>(null)

const { items, loadingItems, newItemName, loadItems, addItem, toggleItem, removeItem, clearChecked } =
  useShoppingItems(teamId, selectedList)

async function loadLists() {
  loading.value = true
  try {
    const res = await shoppingApi.listShoppingLists(teamId)
    lists.value = res.data
    if (res.data.length > 0 && !selectedList.value) {
      await selectList(res.data[0])
    }
  } catch {
    showError('買い物リストの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function selectList(list: ShoppingListResponse) {
  selectedList.value = list
  await loadItems()
}

function openCreateList() {
  editingList.value = null
  listName.value = ''
  isTemplate.value = false
  showListDialog.value = true
}

function openEditList(list: ShoppingListResponse) {
  editingList.value = list
  listName.value = list.name
  isTemplate.value = list.template
  showListDialog.value = true
}

async function saveList() {
  try {
    if (editingList.value) {
      await shoppingApi.updateShoppingList(teamId, editingList.value.id, {
        name: listName.value,
        isTemplate: isTemplate.value,
      })
    } else {
      await shoppingApi.createShoppingList(teamId, {
        name: listName.value,
        isTemplate: isTemplate.value,
      })
    }
    showListDialog.value = false
    await loadLists()
  } catch {
    showError('保存に失敗しました')
  }
}

async function removeList(list: ShoppingListResponse) {
  if (!confirm(`「${list.name}」を削除しますか？`)) return
  try {
    await shoppingApi.deleteShoppingList(teamId, list.id)
    if (selectedList.value?.id === list.id) {
      selectedList.value = null
      items.value = []
    }
    await loadLists()
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(async () => {
  await loadPermissions()
  await loadLists()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">買い物リスト</h1>
      <Button label="リスト作成" icon="pi pi-plus" @click="openCreateList" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="grid gap-6 lg:grid-cols-3">
      <!-- リスト一覧 -->
      <div class="lg:col-span-1">
        <div class="flex flex-col gap-2">
          <div
            v-for="list in lists"
            :key="list.id"
            class="flex cursor-pointer items-center justify-between rounded-xl border p-3 transition-colors"
            :class="
              selectedList?.id === list.id
                ? 'border-primary bg-primary/5'
                : 'border-surface-200 bg-surface-0 hover:bg-surface-50 dark:border-surface-600 dark:bg-surface-800'
            "
            @click="selectList(list)"
          >
            <div>
              <p class="font-medium">{{ list.name }}</p>
              <div class="flex gap-1">
                <Tag
                  v-if="list.template"
                  value="テンプレート"
                  severity="secondary"
                  class="text-xs"
                />
                <Tag
                  v-if="list.status === 'ARCHIVED'"
                  value="アーカイブ"
                  severity="warn"
                  class="text-xs"
                />
              </div>
            </div>
            <div v-if="isAdminOrDeputy" class="flex gap-1">
              <Button
                icon="pi pi-pencil"
                text
                rounded
                size="small"
                @click.stop="openEditList(list)"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                @click.stop="removeList(list)"
              />
            </div>
          </div>
          <div v-if="lists.length === 0" class="py-4 text-center text-surface-400">
            リストがありません
          </div>
        </div>
      </div>

      <!-- アイテム一覧 -->
      <div class="lg:col-span-2">
        <div
          v-if="selectedList"
          class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-4 flex items-center justify-between">
            <h2 class="text-lg font-semibold">{{ selectedList.name }}</h2>
            <Button
              v-if="items.some((i) => i.checked)"
              label="チェック済みを削除"
              text
              size="small"
              severity="danger"
              @click="clearChecked"
            />
          </div>

          <!-- 新規アイテム追加 -->
          <div class="mb-4 flex gap-2">
            <InputText
              v-model="newItemName"
              class="flex-1"
              placeholder="アイテムを追加..."
              @keyup.enter="addItem"
            />
            <Button icon="pi pi-plus" @click="addItem" />
          </div>

          <PageLoading v-if="loadingItems" size="30px" />

          <div v-else class="flex flex-col gap-1">
            <div
              v-for="item in items"
              :key="item.id"
              class="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-surface-50 dark:hover:bg-surface-700"
            >
              <Checkbox
                :model-value="item.checked"
                :binary="true"
                @update:model-value="toggleItem(item)"
              />
              <span class="flex-1" :class="item.checked ? 'text-surface-400 line-through' : ''">
                {{ item.name }}
                <span v-if="item.quantity" class="ml-1 text-sm text-surface-400"
                  >({{ item.quantity }})</span
                >
              </span>
              <span v-if="item.note" class="text-xs text-surface-400">{{ item.note }}</span>
              <Button
                icon="pi pi-times"
                text
                rounded
                size="small"
                severity="secondary"
                @click="removeItem(item)"
              />
            </div>
            <div v-if="items.length === 0" class="py-4 text-center text-surface-400">
              アイテムがありません
            </div>
          </div>
        </div>
        <div
          v-else
          class="rounded-xl border border-surface-300 bg-surface-0 p-8 text-center text-surface-400 dark:border-surface-600 dark:bg-surface-800"
        >
          リストを選択してください
        </div>
      </div>
    </div>

    <!-- リスト作成/編集ダイアログ -->
    <Dialog
      v-model:visible="showListDialog"
      :header="editingList ? 'リストを編集' : 'リストを作成'"
      modal
      class="w-full max-w-md"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">リスト名</label>
          <InputText v-model="listName" class="w-full" placeholder="例: 週末の買い物" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="isTemplate" :binary="true" input-id="isTemplate" />
          <label for="isTemplate" class="text-sm">テンプレートとして保存</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showListDialog = false" />
        <Button :label="editingList ? '更新' : '作成'" @click="saveList" />
      </template>
    </Dialog>
  </div>
</template>
