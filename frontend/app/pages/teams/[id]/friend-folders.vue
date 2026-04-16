<script setup lang="ts">
import type { TeamFriendFolderView } from '~/types/social-friend'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const api = useFriendFoldersApi()
const notification = useNotification()
const { can, loadPermissions } = useRoleAccess('team', teamId)

// ----- 状態 -----
const loading = ref(true)
const folders = ref<TeamFriendFolderView[]>([])

// ----- ダイアログ -----
const showFolderDialog = ref(false)
const editingFolder = ref<TeamFriendFolderView | null>(null)
const folderForm = ref<{
  name: string
  description: string
  color: string
}>({
  name: '',
  description: '',
  color: '',
})

// ----- 削除確認ダイアログ -----
const showDeleteDialog = ref(false)
const deletingFolder = ref<TeamFriendFolderView | null>(null)

// ----- 権限チェック -----
const canManage = computed(() => can('MANAGE_FRIEND_TEAMS'))

// ----- フォルダ一覧取得 -----
async function loadFolders() {
  loading.value = true
  try {
    const res = await api.listFolders(teamId.value)
    folders.value = res.data
  }
  catch (error) {
    api.handleApiError(error, 'フォルダ一覧取得')
  }
  finally {
    loading.value = false
  }
}

// ----- フォルダ作成ダイアログを開く -----
function openCreateDialog() {
  editingFolder.value = null
  folderForm.value = { name: '', description: '', color: '' }
  showFolderDialog.value = true
}

// ----- フォルダ編集ダイアログを開く -----
function openEditDialog(folder: TeamFriendFolderView) {
  editingFolder.value = folder
  folderForm.value = {
    name: folder.name,
    description: folder.description ?? '',
    color: folder.color ?? '',
  }
  showFolderDialog.value = true
}

// ----- フォルダ保存（作成 or 更新）-----
async function saveFolder() {
  try {
    const body = {
      name: folderForm.value.name,
      description: folderForm.value.description || undefined,
      color: folderForm.value.color || undefined,
    }
    if (editingFolder.value) {
      await api.updateFolder(teamId.value, editingFolder.value.id, body)
    }
    else {
      await api.createFolder(teamId.value, body)
    }
    showFolderDialog.value = false
    await loadFolders()
  }
  catch (error) {
    api.handleApiError(error, 'フォルダ保存')
  }
}

// ----- 削除確認ダイアログを開く -----
function openDeleteDialog(folder: TeamFriendFolderView) {
  deletingFolder.value = folder
  showDeleteDialog.value = true
}

// ----- フォルダ削除 -----
async function confirmDeleteFolder() {
  if (!deletingFolder.value) return
  try {
    await api.deleteFolder(teamId.value, deletingFolder.value.id)
    notification.success('フォルダを削除しました')
    showDeleteDialog.value = false
    deletingFolder.value = null
    await loadFolders()
  }
  catch (error) {
    api.handleApiError(error, 'フォルダ削除')
  }
}

// ----- 初期ロード -----
onMounted(async () => {
  await loadPermissions()
  if (canManage.value) {
    await loadFolders()
  }
})

watch(canManage, (val) => {
  if (val && folders.value.length === 0 && !loading.value) {
    loadFolders()
  }
})
</script>

<template>
  <div>
    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="t('team.friendFolders.title')" />
      <Button
        v-if="canManage"
        :label="t('team.friendFolders.create')"
        icon="pi pi-plus"
        @click="openCreateDialog"
      />
    </div>

    <!-- 権限なし -->
    <div v-if="!loading && !canManage" class="py-16 text-center text-surface-500">
      <i class="pi pi-lock mb-4 text-4xl" />
      <p>このページへのアクセス権限がありません。</p>
    </div>

    <!-- ローディング -->
    <div v-else-if="loading && folders.length === 0" class="flex justify-center py-12">
      <ProgressSpinner style="width: 48px; height: 48px" />
    </div>

    <!-- フォルダ一覧 -->
    <template v-else-if="canManage">
      <!-- 空状態 -->
      <div v-if="folders.length === 0" class="py-16 text-center text-surface-500">
        <i class="pi pi-folder-open mb-4 text-4xl" />
        <p>{{ t('team.friendFolders.empty') }}</p>
      </div>

      <!-- 一覧 -->
      <div v-else class="space-y-3">
        <div
          v-for="folder in folders"
          :key="folder.id"
          class="flex items-start gap-4 rounded-xl border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900"
        >
          <!-- カラーインジケーター -->
          <div
            class="mt-1 h-4 w-4 flex-shrink-0 rounded-full"
            :style="folder.color ? { backgroundColor: folder.color } : {}"
            :class="{ 'bg-surface-300 dark:bg-surface-600': !folder.color }"
          />

          <!-- フォルダ情報 -->
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="font-semibold text-surface-800 dark:text-surface-100">
                {{ folder.name }}
              </span>
              <Tag
                v-if="folder.isDefault"
                :value="t('team.friendFolders.default')"
                severity="secondary"
                class="text-xs"
              />
            </div>
            <p v-if="folder.description" class="mt-1 text-sm text-surface-500 dark:text-surface-400">
              {{ folder.description }}
            </p>
            <p class="mt-1 text-xs text-surface-400 dark:text-surface-500">
              {{ t('team.friendFolders.memberCount') }}: {{ folder.memberCount }}
            </p>
          </div>

          <!-- 操作ボタン -->
          <div class="flex flex-shrink-0 gap-1">
            <Button
              icon="pi pi-pencil"
              size="small"
              text
              severity="secondary"
              :aria-label="t('team.friendFolders.edit')"
              @click="openEditDialog(folder)"
            />
            <Button
              v-if="!folder.isDefault"
              icon="pi pi-trash"
              size="small"
              text
              severity="danger"
              :aria-label="t('team.friendFolders.delete')"
              @click="openDeleteDialog(folder)"
            />
          </div>
        </div>
      </div>
    </template>

    <!-- フォルダ作成 / 編集ダイアログ -->
    <Dialog
      v-model:visible="showFolderDialog"
      :header="editingFolder ? t('team.friendFolders.edit') : t('team.friendFolders.create')"
      :modal="true"
      :style="{ width: '420px' }"
    >
      <div class="flex flex-col gap-4">
        <!-- フォルダ名 -->
        <label class="flex flex-col gap-1">
          <span class="text-sm font-medium">
            {{ t('team.friendFolders.name') }} <span class="text-red-500">*</span>
          </span>
          <InputText
            v-model="folderForm.name"
            class="w-full"
            :placeholder="t('team.friendFolders.name')"
          />
        </label>

        <!-- 説明 -->
        <label class="flex flex-col gap-1">
          <span class="text-sm font-medium">{{ t('team.friendFolders.description') }}</span>
          <Textarea
            v-model="folderForm.description"
            class="w-full"
            :rows="3"
            :placeholder="t('team.friendFolders.description')"
          />
        </label>

        <!-- カラー -->
        <label class="flex flex-col gap-1">
          <span class="text-sm font-medium">{{ t('team.friendFolders.color') }}</span>
          <div class="flex items-center gap-2">
            <InputText
              v-model="folderForm.color"
              class="flex-1"
              placeholder="#3B82F6"
            />
            <div
              class="h-9 w-9 flex-shrink-0 rounded-lg border border-surface-300"
              :style="folderForm.color ? { backgroundColor: folderForm.color } : {}"
              :class="{ 'bg-surface-200 dark:bg-surface-700': !folderForm.color }"
            />
          </div>
        </label>
      </div>

      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="showFolderDialog = false"
        />
        <Button
          :label="editingFolder ? t('button.save') : t('button.create')"
          icon="pi pi-check"
          :disabled="!folderForm.name.trim()"
          @click="saveFolder"
        />
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeleteDialog"
      :header="t('team.friendFolders.delete')"
      :modal="true"
      :style="{ width: '380px' }"
    >
      <p class="text-surface-700 dark:text-surface-200">
        {{ t('team.friendFolders.deleteConfirm') }}
      </p>
      <p v-if="deletingFolder" class="mt-2 font-semibold text-surface-900 dark:text-surface-100">
        "{{ deletingFolder.name }}"
      </p>

      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="showDeleteDialog = false"
        />
        <Button
          :label="t('team.friendFolders.delete')"
          icon="pi pi-trash"
          severity="danger"
          @click="confirmDeleteFolder"
        />
      </template>
    </Dialog>
  </div>
</template>
