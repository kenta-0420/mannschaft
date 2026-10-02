<script setup lang="ts">
import type { SharedFolder, SharedFile, FileVisibilityRole } from '~/types/filesharing'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION' | 'PERSONAL'
  scopeId?: string
}>()

const {
  getFolder,
  getFolders,
  getMyFolders,
  getDownloadUrl,
  deleteFile,
  createFolder,
  createMyFolder,
  presignUpload,
  registerFile,
} = useFileSharingApi()
const { showSuccess, showError } = useNotification()
const { relativeTime } = useRelativeTime()
const { t } = useI18n()

const currentFolderId = ref<number | null>(null)
const folders = ref<SharedFolder[]>([])
const files = ref<SharedFile[]>([])
const breadcrumbs = ref<Array<{ id: number; name: string }>>([])
const loading = ref(false)
const showNewFolderDialog = ref(false)
const newFolderName = ref('')
// F05.5 (B/C) 新規フォルダのセキュリティ設定
const newFolderMinRole = ref<FileVisibilityRole | null>(null)
const newFolderDownloadDisabled = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

// F05.5 使い方ガイド・ファイル設定・公開リンクの各ダイアログ状態
const showGuideModal = ref(false)
const showSettingsDialog = ref(false)
const showShareDialog = ref(false)
const activeFile = ref<SharedFile | null>(null)

const isPersonal = computed(() => props.scopeType === 'PERSONAL')

/** 最低可視ロールの表示ラベル（バッジ用）。null は表示しない。 */
function roleLabel(role: FileVisibilityRole | null | undefined): string | null {
  if (!role) return null
  return t(`file_sharing.visibility.${role}`)
}

function openSettings(file: SharedFile) {
  activeFile.value = file
  showSettingsDialog.value = true
}

function openShare(file: SharedFile) {
  activeFile.value = file
  showShareDialog.value = true
}

async function loadFolder(folderId: number | null) {
  loading.value = true
  try {
    if (folderId) {
      const res = await getFolder(folderId)
      folders.value = res.data.subfolders
      files.value = res.data.files
      breadcrumbs.value = res.data.breadcrumbs
      currentFolderId.value = folderId
    } else if (isPersonal.value) {
      const res = await getMyFolders()
      folders.value = res.data
      files.value = []
      breadcrumbs.value = []
      currentFolderId.value = null
    } else {
      const res = await getFolders(props.scopeType, props.scopeId!)
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
  // F05.5 (B/C) セキュリティ設定を作成リクエストに含める（未指定＝制限なし）
  const security = {
    minVisibleRole: newFolderMinRole.value ?? undefined,
    downloadDisabled: newFolderDownloadDisabled.value,
  }
  try {
    if (isPersonal.value) {
      // バグ修正: scopeType: 'PERSONAL' が必要（欠落すると 400）
      await createMyFolder({
        scopeType: 'PERSONAL',
        parentId: currentFolderId.value,
        name: newFolderName.value.trim(),
        ...security,
      })
    } else {
      await createFolder({
        scopeType: props.scopeType,
        scopeId: props.scopeId,
        parentId: currentFolderId.value,
        name: newFolderName.value.trim(),
        ...security,
      })
    }
    showSuccess('フォルダを作成しました')
    showNewFolderDialog.value = false
    newFolderName.value = ''
    newFolderMinRole.value = null
    newFolderDownloadDisabled.value = false
    loadFolder(currentFolderId.value)
  } catch {
    showError('作成に失敗しました')
  }
}

/** ファイル選択ダイアログを開く。フォルダ内でのみアップロード可能。 */
function triggerFileInput() {
  if (currentFolderId.value === null) {
    showError(t('settings.fileBrowser.upload_need_folder'))
    return
  }
  fileInputRef.value?.click()
}

/** ファイル選択後の処理: presign → R2 PUT → registerFile → 一覧更新 */
async function onFilesSelected(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files || input.files.length === 0) return
  if (currentFolderId.value === null) return

  const folderId = currentFolderId.value
  uploading.value = true
  const selectedFiles = Array.from(input.files)

  for (const file of selectedFiles) {
    const mimeType = file.type || 'application/octet-stream'
    try {
      // 1. Presigned URL を取得
      const presignRes = await presignUpload({
        folderId,
        fileName: file.name,
        contentType: mimeType,
        fileSize: file.size,
      })
      const { uploadUrl, fileKey } = presignRes.data

      // 2. ストレージへ直接 PUT（credentials なし・Content-Type 一致）
      const putRes = await fetch(uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': mimeType },
      })
      if (!putRes.ok) {
        throw new Error(`Storage PUT failed: ${putRes.status}`)
      }

      // 3. ファイルレコードを登録
      // BE CreateFileRequest は name/contentType を期待（fileName/mimeType ではない）
      await registerFile({
        folderId,
        fileKey,
        name: file.name,
        contentType: mimeType,
        fileSize: file.size,
      })

      showSuccess(t('settings.fileBrowser.upload_success', { name: file.name }))
    } catch {
      showError(t('settings.fileBrowser.upload_error', { name: file.name }))
    }
  }

  uploading.value = false
  // 一覧を再取得して反映
  await loadFolder(currentFolderId.value)

  // 同じファイルを再選択できるよう input をリセット
  input.value = ''
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
      <Button
        :label="t('settings.fileBrowser.upload_button')"
        icon="pi pi-upload"
        text
        size="small"
        :loading="uploading"
        @click="triggerFileInput"
      />
      <!-- F05.5 使い方ガイド -->
      <Button
        :label="t('button.help')"
        icon="pi pi-question-circle"
        text
        size="small"
        class="ml-auto"
        data-testid="file-sharing-help"
        @click="showGuideModal = true"
      />
      <!-- 非表示のファイル入力（multiple で複数ファイル対応） -->
      <input
        ref="fileInputRef"
        type="file"
        multiple
        class="hidden"
        data-testid="file-upload-input"
        @change="onFilesSelected"
      >
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <div v-else class="flex flex-col gap-1">
      <!-- フォルダ -->
      <button
        v-for="folder in folders"
        :key="`f-${folder.id}`"
        class="flex items-center gap-3 rounded-lg px-4 py-3 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
        @click="loadFolder(folder.id)"
      >
        <i class="pi pi-folder text-xl text-amber-500" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">{{ folder.name }}</p>
          <div class="flex items-center gap-2">
            <p class="text-xs text-surface-400">{{ folder.fileCount }}ファイル</p>
            <Tag
              v-if="roleLabel(folder.minVisibleRole)"
              :value="roleLabel(folder.minVisibleRole) ?? ''"
              severity="secondary"
              class="text-xs"
            />
            <i
              v-if="folder.downloadDisabled"
              class="pi pi-ban text-xs text-surface-400"
              :title="t('file_sharing.download.disabledLabel')"
            />
          </div>
        </div>
      </button>

      <!-- ファイル -->
      <div
        v-for="file in files"
        :key="`file-${file.id}`"
        class="flex items-center gap-3 rounded-lg px-4 py-3 transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
      >
        <i :class="getFileIcon(file.mimeType)" class="text-xl text-surface-500" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">{{ file.fileName }}</p>
          <div class="flex flex-wrap items-center gap-2 text-xs text-surface-400">
            <span>{{ formatSize(file.fileSize) }}</span>
            <span>{{ file.uploadedBy?.displayName }}</span>
            <span>{{ relativeTime(file.createdAt) }}</span>
            <span v-if="file.versionCount > 1">v{{ file.versionCount }}</span>
            <Tag
              v-if="roleLabel(file.minVisibleRole)"
              :value="roleLabel(file.minVisibleRole) ?? ''"
              severity="secondary"
              class="text-xs"
            />
            <Tag
              v-if="file.downloadDisabled"
              :value="t('file_sharing.download.disabledLabel')"
              severity="warn"
              class="text-xs"
            />
          </div>
        </div>
        <div class="flex items-center gap-1">
          <!-- ダウンロード禁止ファイルは DL ボタンを抑止する（C）。表示防止はできない旨はガイドに明記。 -->
          <Button
            v-if="!file.downloadDisabled"
            icon="pi pi-download"
            text
            rounded
            size="small"
            :aria-label="t('file_sharing.sharedPage.downloadButton')"
            @click="onDownload(file)"
          />
          <Button
            icon="pi pi-link"
            text
            rounded
            size="small"
            :aria-label="t('file_sharing.publicLink.title')"
            data-testid="file-share-open"
            @click="openShare(file)"
          />
          <Button
            icon="pi pi-cog"
            text
            rounded
            size="small"
            :aria-label="t('file_sharing.settings.title')"
            data-testid="file-settings-open"
            @click="openSettings(file)"
          />
          <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="onDeleteFile(file)" />
        </div>
      </div>

      <div v-if="folders.length === 0 && files.length === 0" class="py-12 text-center">
        <i class="pi pi-folder-open mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">ファイルがありません</p>
      </div>
    </div>

    <!-- 新規フォルダダイアログ -->
    <Dialog v-model:visible="showNewFolderDialog" header="フォルダ作成" modal class="w-full max-w-md">
      <div class="flex flex-col gap-4">
        <InputText v-model="newFolderName" class="w-full" placeholder="フォルダ名" />
        <!-- F05.5 (B/C) セキュリティ設定 -->
        <FileSecurityFields
          v-model:min-visible-role="newFolderMinRole"
          v-model:download-disabled="newFolderDownloadDisabled"
        />
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showNewFolderDialog = false" />
        <Button :label="t('button.create')" :disabled="!newFolderName.trim()" @click="onCreateFolder" />
      </template>
    </Dialog>

    <!-- F05.5 使い方ガイド -->
    <FileSharingGuideModal v-model:visible="showGuideModal" />

    <!-- F05.5 (B/C) ファイル設定・(D) 公開リンク -->
    <FileSettingsDialog
      v-if="activeFile"
      v-model:visible="showSettingsDialog"
      :file="activeFile"
      @updated="loadFolder(currentFolderId)"
    />
    <FileShareLinkDialog
      v-if="activeFile"
      v-model:visible="showShareDialog"
      :file="activeFile"
    />
  </div>
</template>
