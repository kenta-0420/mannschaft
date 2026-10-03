<script setup lang="ts">
import type { ScopeFolder } from '~/types/scopeFolder'

interface ScopeItem {
  id: number
  /** チーム/組織 UUID（ルートナビゲーションに使用） */
  slug?: string
  name: string
  nickname1: string | null
  iconUrl: string | null
  memberCount: number
  template?: string
}

interface Props {
  scopeType: 'TEAM' | 'ORGANIZATION'
  items: ScopeItem[]
}

const props = defineProps<Props>()

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const folderApi = useScopeFolderApi()

const folders = ref<ScopeFolder[]>([])
const expandedFolders = ref<Set<number>>(new Set())
const showEditDialog = ref(false)
const editTarget = ref<ScopeFolder | undefined>(undefined)
const loading = ref(false)

const FOLDER_LIMIT = 20

async function loadFolders() {
  loading.value = true
  try {
    folders.value = await folderApi.getFolders(props.scopeType)
    // 全フォルダをデフォルトで展開
    folders.value.forEach(f => expandedFolders.value.add(f.id))
  }
  catch {
    toast.add({
      severity: 'error',
      summary: t('error.unknown'),
      detail: t('error.network'),
      life: 3000,
    })
  }
  finally {
    loading.value = false
  }
}

onMounted(loadFolders)

// フォルダに属しているアイテムのScopeIdセット
const assignedScopeIds = computed(() => {
  const ids = new Set<string>()
  for (const folder of folders.value) {
    for (const scopeId of folder.itemScopeIds) {
      ids.add(scopeId)
    }
  }
  return ids
})

// 未分類アイテム
const uncategorizedItems = computed(() =>
  props.items.filter(item => !assignedScopeIds.value.has(String(item.id))),
)

// フォルダ内アイテムを取得
function folderItems(folder: ScopeFolder): ScopeItem[] {
  return folder.itemScopeIds
    .map(id => props.items.find(item => String(item.id) === id))
    .filter((item): item is ScopeItem => item !== undefined)
}

function toggleFolder(folderId: number) {
  if (expandedFolders.value.has(folderId)) {
    expandedFolders.value.delete(folderId)
  }
  else {
    expandedFolders.value.add(folderId)
  }
}

function openCreateDialog() {
  if (folders.value.length >= FOLDER_LIMIT) {
    toast.add({
      severity: 'warn',
      summary: t('scopeFolder.limitExceeded'),
      life: 3000,
    })
    return
  }
  editTarget.value = undefined
  showEditDialog.value = true
}

function openEditDialog(folder: ScopeFolder) {
  editTarget.value = folder
  showEditDialog.value = true
}

function onFolderSaved(folder: ScopeFolder) {
  const idx = folders.value.findIndex(f => f.id === folder.id)
  if (idx >= 0) {
    folders.value[idx] = folder
  }
  else {
    folders.value.push(folder)
    expandedFolders.value.add(folder.id)
  }
}

function confirmDelete(folder: ScopeFolder) {
  confirm.require({
    message: t('scopeFolder.deleteConfirm'),
    header: t('scopeFolder.deleteFolder'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: () => doDelete(folder),
  })
}

async function doDelete(folder: ScopeFolder) {
  try {
    await folderApi.deleteFolder(folder.id)
    folders.value = folders.value.filter(f => f.id !== folder.id)
    expandedFolders.value.delete(folder.id)
  }
  catch {
    toast.add({
      severity: 'error',
      summary: t('error.unknown'),
      detail: t('error.network'),
      life: 3000,
    })
  }
}

// どのフォルダへ移動するかのドロップダウン制御
const movingItemId = ref<number | null>(null)

function toggleMoveMenu(itemId: number) {
  movingItemId.value = movingItemId.value === itemId ? null : itemId
}

// アイテムが属しているフォルダID（複数フォルダは非対応、先頭のみ）
function itemCurrentFolderId(itemId: number): number | null {
  const folder = folders.value.find(f => f.itemScopeIds.includes(String(itemId)))
  return folder ? folder.id : null
}

async function moveItemToFolder(itemId: number, targetFolderId: number | null) {
  const currentFolderId = itemCurrentFolderId(itemId)

  // 現在のフォルダから外す
  if (currentFolderId !== null) {
    try {
      await folderApi.removeItem(currentFolderId, String(itemId))
      const folder = folders.value.find(f => f.id === currentFolderId)
      if (folder) {
        folder.itemScopeIds = folder.itemScopeIds.filter(id => id !== String(itemId))
      }
    }
    catch {
      toast.add({
        severity: 'error',
        summary: t('error.unknown'),
        detail: t('error.network'),
        life: 3000,
      })
      movingItemId.value = null
      return
    }
  }

  // 新しいフォルダへ追加
  if (targetFolderId !== null) {
    try {
      await folderApi.addItem(targetFolderId, String(itemId))
      const folder = folders.value.find(f => f.id === targetFolderId)
      if (folder && !folder.itemScopeIds.includes(String(itemId))) {
        folder.itemScopeIds.push(String(itemId))
      }
    }
    catch {
      toast.add({
        severity: 'error',
        summary: t('error.unknown'),
        detail: t('error.network'),
        life: 3000,
      })
    }
  }

  movingItemId.value = null
}

// フォルダのカラースタイル
function folderColorStyle(color: string | null): Record<string, string> {
  if (!color) return {}
  return { borderLeftColor: color, borderLeftWidth: '4px' }
}

// クリックアウトサイドでドロップダウンを閉じる
function handleClickOutside(event: MouseEvent) {
  const target = event.target as Element
  if (!target.closest('[data-move-menu]')) {
    movingItemId.value = null
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div>
    <!-- フォルダ一覧 -->
    <div v-if="loading" class="flex justify-center py-8">
      <i class="pi pi-spin pi-spinner text-2xl text-surface-400" />
    </div>

    <div v-else>
      <!-- フォルダなし -->
      <div
        v-if="folders.length === 0 && uncategorizedItems.length === 0"
        class="py-8 text-center text-surface-400"
      >
        {{ $t('scopeFolder.noFolders') }}
      </div>

      <!-- フォルダ一覧 -->
      <div class="flex flex-col gap-3">
        <div
          v-for="folder in folders"
          :key="folder.id"
          class="rounded-lg border border-surface-200 bg-surface-50"
          :style="folderColorStyle(folder.color)"
        >
          <!-- フォルダヘッダー -->
          <div
            class="flex cursor-pointer items-center gap-2 px-4 py-3"
            @click="toggleFolder(folder.id)"
          >
            <i
              class="pi text-surface-400 transition-transform"
              :class="expandedFolders.has(folder.id) ? 'pi-chevron-down' : 'pi-chevron-right'"
            />
            <i class="pi pi-folder text-surface-500" />
            <span class="flex-1 font-semibold">{{ folder.name }}</span>
            <span class="text-sm text-surface-400">
              {{ folder.itemScopeIds.length }}
            </span>

            <!-- 編集・削除ボタン -->
            <div class="flex items-center gap-1" @click.stop>
              <Button
                icon="pi pi-pencil"
                size="small"
                severity="secondary"
                text
                :aria-label="$t('scopeFolder.editFolder')"
                @click="openEditDialog(folder)"
              />
              <Button
                icon="pi pi-trash"
                size="small"
                severity="danger"
                text
                :aria-label="$t('scopeFolder.deleteFolder')"
                @click="confirmDelete(folder)"
              />
            </div>
          </div>

          <!-- フォルダ内アイテム -->
          <div
            v-if="expandedFolders.has(folder.id)"
            class="grid grid-cols-1 gap-2 px-4 pb-3 sm:grid-cols-2 lg:grid-cols-3"
          >
            <div
              v-for="item in folderItems(folder)"
              :key="item.id"
              class="relative flex cursor-pointer items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3 transition-shadow hover:shadow-sm"
            >
              <Avatar
                :image="item.iconUrl ?? undefined"
                :label="item.iconUrl ? undefined : (item.nickname1 || item.name).charAt(0)"
                shape="circle"
                size="normal"
              />
              <div class="min-w-0 flex-1" @click="item.slug ? navigateTo(scopeType === 'TEAM' ? `/teams/${item.slug}` : `/organizations/${item.slug}`) : undefined">
                <span class="block truncate font-semibold">{{ item.nickname1 || item.name }}</span>
                <span class="text-xs text-surface-400">
                  <i class="pi pi-users mr-1" />{{ item.memberCount }}
                </span>
              </div>

              <!-- フォルダ移動ボタン -->
              <div data-move-menu class="relative">
                <Button
                  icon="pi pi-folder-open"
                  size="small"
                  severity="secondary"
                  text
                  :aria-label="$t('scopeFolder.moveToFolder')"
                  @click.stop="toggleMoveMenu(item.id)"
                />
                <div
                  v-if="movingItemId === item.id"
                  class="absolute right-0 top-8 z-50 min-w-[160px] rounded-lg border border-surface-200 bg-surface-0 shadow-lg"
                >
                  <!-- 「フォルダから外す」オプション -->
                  <button
                    v-if="itemCurrentFolderId(item.id) !== null"
                    type="button"
                    class="flex w-full items-center gap-2 px-3 py-2 text-sm text-left hover:bg-surface-50"
                    @click="moveItemToFolder(item.id, null)"
                  >
                    <i class="pi pi-times-circle text-surface-400" />
                    {{ $t('scopeFolder.removeFromFolder') }}
                  </button>
                  <!-- フォルダ一覧 -->
                  <button
                    v-for="f in folders"
                    :key="f.id"
                    type="button"
                    class="flex w-full items-center gap-2 px-3 py-2 text-sm text-left hover:bg-surface-50"
                    :class="itemCurrentFolderId(item.id) === f.id ? 'bg-primary-50 font-semibold' : ''"
                    @click="moveItemToFolder(item.id, f.id)"
                  >
                    <span
                      v-if="f.color"
                      class="inline-block h-3 w-3 rounded-full"
                      :style="{ backgroundColor: f.color }"
                    />
                    <i v-else class="pi pi-folder text-surface-400" />
                    {{ f.name }}
                  </button>
                </div>
              </div>
            </div>

            <!-- フォルダが空 -->
            <div
              v-if="folderItems(folder).length === 0"
              class="col-span-full py-4 text-center text-sm text-surface-400"
            >
              {{ $t('scopeFolder.emptyFolder') }}
            </div>
          </div>
        </div>

        <!-- 未分類セクション -->
        <div
          v-if="uncategorizedItems.length > 0"
          class="rounded-lg border border-surface-200 bg-surface-50"
        >
          <div class="flex items-center gap-2 px-4 py-3">
            <i class="pi pi-folder text-surface-400" />
            <span class="flex-1 font-semibold text-surface-500">{{ $t('scopeFolder.uncategorized') }}</span>
            <span class="text-sm text-surface-400">{{ uncategorizedItems.length }}</span>
          </div>
          <div class="grid grid-cols-1 gap-2 px-4 pb-3 sm:grid-cols-2 lg:grid-cols-3">
            <div
              v-for="item in uncategorizedItems"
              :key="item.id"
              class="relative flex cursor-pointer items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3 transition-shadow hover:shadow-sm"
            >
              <Avatar
                :image="item.iconUrl ?? undefined"
                :label="item.iconUrl ? undefined : (item.nickname1 || item.name).charAt(0)"
                shape="circle"
                size="normal"
              />
              <div class="min-w-0 flex-1" @click="item.slug ? navigateTo(scopeType === 'TEAM' ? `/teams/${item.slug}` : `/organizations/${item.slug}`) : undefined">
                <span class="block truncate font-semibold">{{ item.nickname1 || item.name }}</span>
                <span class="text-xs text-surface-400">
                  <i class="pi pi-users mr-1" />{{ item.memberCount }}
                </span>
              </div>

              <!-- フォルダ移動ボタン -->
              <div data-move-menu class="relative">
                <Button
                  icon="pi pi-folder-open"
                  size="small"
                  severity="secondary"
                  text
                  :aria-label="$t('scopeFolder.moveToFolder')"
                  @click.stop="toggleMoveMenu(item.id)"
                />
                <div
                  v-if="movingItemId === item.id"
                  class="absolute right-0 top-8 z-50 min-w-[160px] rounded-lg border border-surface-200 bg-surface-0 shadow-lg"
                >
                  <button
                    v-for="f in folders"
                    :key="f.id"
                    type="button"
                    class="flex w-full items-center gap-2 px-3 py-2 text-sm text-left hover:bg-surface-50"
                    @click="moveItemToFolder(item.id, f.id)"
                  >
                    <span
                      v-if="f.color"
                      class="inline-block h-3 w-3 rounded-full"
                      :style="{ backgroundColor: f.color }"
                    />
                    <i v-else class="pi pi-folder text-surface-400" />
                    {{ f.name }}
                  </button>
                  <div
                    v-if="folders.length === 0"
                    class="px-3 py-2 text-sm text-surface-400"
                  >
                    {{ $t('scopeFolder.noFolders') }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- フォルダ追加ボタン -->
      <div class="mt-4">
        <Button
          :label="$t('scopeFolder.addFolder')"
          icon="pi pi-plus"
          severity="secondary"
          outlined
          size="small"
          @click="openCreateDialog"
        />
      </div>
    </div>

    <!-- フォルダ作成・編集ダイアログ -->
    <ScopeFolderEditDialog
      v-model:visible="showEditDialog"
      :scope-type="scopeType"
      :edit-target="editTarget"
      @saved="onFolderSaved"
    />
  </div>
</template>
