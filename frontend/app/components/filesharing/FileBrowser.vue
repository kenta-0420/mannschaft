<script setup lang="ts">
import type { SharedFolder, SharedFile } from '~/types/filesharing'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const { getFolder, getFolders, getDownloadUrl, deleteFile, deleteFolder, createFolder } = useFileSharingApi()
const { showSuccess, showError } = useNotification()
const { relativeTime } = useRelativeTime()

const currentFolderId = ref<number | null>(null)
const folders = ref<SharedFolder[]>([])
const files = ref<SharedFile[]>([])
const breadcrumbs = ref<Array<{ id: number; name: string }>>([])
const loading = ref(false)
const showNewFolderDialog = ref(false)
const newFolderName = ref('')

async function loadFolder(folderId: number | null) {
  loading.value = true
  try {
    if (folderId) {
      const res = await getFolder(folderId)
      folders.value = res.data.subfolders
      files.value = res.data.files
      breadcrumbs.value = res.data.breadcrumbs
      currentFolderId.value = folderId
    } else {
      const res = await getFolders(props.scopeType, props.scopeId)
      folders.value = res.data
      files.value = []
      breadcrumbs.value = []
      currentFolderId.value = null
    }
  } catch {
    showError('フォルダの読み込みに失敗しました')
  } finally {
    loading.value = false
  }
}

async function onDownload(file: SharedFile) {
  try {
    const res = await getDownloadUrl(file.id)
    window.open(res.data.downloadUrl, '_blank')
  } catch {
    showError('ダウンロードに失敗しました')
  }
}

async function onDeleteFile(file: SharedFile) {
  try {
    await deleteFile(file.id)
    files.value = files.value.filter(f => f.id !== file.id)
    showSuccess('ファイルを削除しました')
  } catch {
    showError('削除に失敗しました')
  }
}

async function onCreateFolder() {
  if (!newFolderName.value.trim()) return
  try {
    await createFolder({
      scopeType: props.scopeType,
      scopeId: props.scopeId,
      parentId: currentFolderId.value,
      name: newFolderName.value.trim(),
    })
    showSuccess('フォルダを作成しました')
    showNewFolderDialog.value = false
    newFolderName.value = ''
    loadFolder(currentFolderId.value)
  } catch {
    showError('作成に失敗しました')
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function getFileIcon(mimeType: string): string {
  if (mimeType.startsWith('image/')) return 'pi pi-image'
  if (mimeType.includes('pdf')) return 'pi pi-file-pdf'
  if (mimeType.includes('spreadsheet') || mimeType.includes('excel')) return 'pi pi-file-excel'
  if (mimeType.includes('word') || mimeType.includes('document')) return 'pi pi-file-word'
  return 'pi pi-file'
}

onMounted(() => loadFolder(null))
</script>

<template>
  <div>
    <!-- ブレッドクラム -->
    <div class="mb-4 flex items-center gap-2 text-sm">
      <button class="text-primary hover:underline" @click="loadFolder(null)">ルート</button>
      <template v-for="bc in breadcrumbs" :key="bc.id">
        <i class="pi pi-chevron-right text-xs text-surface-400" />
        <button class="text-primary hover:underline" @click="loadFolder(bc.id)">{{ bc.name }}</button>
      </template>
    </div>

    <!-- アクション -->
    <div class="mb-4 flex items-center gap-2">
      <Button label="フォルダ作成" icon="pi pi-folder-plus" text size="small" @click="showNewFolderDialog = true" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="flex flex-col gap-1">
      <!-- フォルダ -->
      <button
        v-for="folder in folders"
        :key="`f-${folder.id}`"
        class="flex items-center gap-3 rounded-lg px-4 py-3 text-left transition-colors hover:bg-surface-100"
        @click="loadFolder(folder.id)"
      >
        <i class="pi pi-folder text-xl text-amber-500" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">{{ folder.name }}</p>
          <p class="text-xs text-surface-400">{{ folder.fileCount }}ファイル</p>
        </div>
      </button>

      <!-- ファイル -->
      <div
        v-for="file in files"
        :key="`file-${file.id}`"
        class="flex items-center gap-3 rounded-lg px-4 py-3 transition-colors hover:bg-surface-50"
      >
        <i :class="getFileIcon(file.mimeType)" class="text-xl text-surface-500" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">{{ file.fileName }}</p>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <span>{{ formatSize(file.fileSize) }}</span>
            <span>{{ file.uploadedBy?.displayName }}</span>
            <span>{{ relativeTime(file.createdAt) }}</span>
            <span v-if="file.versionCount > 1">v{{ file.versionCount }}</span>
          </div>
        </div>
        <div class="flex items-center gap-1">
          <Button icon="pi pi-download" text rounded size="small" @click="onDownload(file)" />
          <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="onDeleteFile(file)" />
        </div>
      </div>

      <div v-if="folders.length === 0 && files.length === 0" class="py-12 text-center">
        <i class="pi pi-folder-open mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">ファイルがありません</p>
      </div>
    </div>

    <!-- 新規フォルダダイアログ -->
    <Dialog v-model:visible="showNewFolderDialog" header="フォルダ作成" modal class="w-full max-w-sm">
      <InputText v-model="newFolderName" class="w-full" placeholder="フォルダ名" />
      <template #footer>
        <Button label="キャンセル" text @click="showNewFolderDialog = false" />
        <Button label="作成" :disabled="!newFolderName.trim()" @click="onCreateFolder" />
      </template>
    </Dialog>
  </div>
</template>
