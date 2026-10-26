<script setup lang="ts">
import type { SharedFolder, SharedFile } from '~/types/filesharing'
import type { TournamentDivision } from '~/types/tournament'

/**
 * F08.7.1 大会ファイル置き場ブラウザ
 * 大会全体フォルダ＋ディビジョン別フォルダをタブで切り替えて表示する。
 * フォルダ作成は isAdmin=true のときのみ表示。
 */
const props = defineProps<{
  tournamentId: number
  divisions: TournamentDivision[]
  isAdmin: boolean
}>()

const { t } = useI18n()
const { listFolders, createFolder, listDivisionFolders, createDivisionFolder } =
  useTournamentFolders()
const { getFolder, getDownloadUrl, deleteFile } = useFileSharingApi()
const { showSuccess, showError } = useNotification()
const { relativeTime } = useRelativeTime()

// タブ: null = 大会全体, number = divisionId
type TabKey = 'tournament' | number
const activeTab = ref<TabKey>('tournament')

// フォルダツリー状態
const currentFolderId = ref<number | null>(null)
const folders = ref<SharedFolder[]>([])
const files = ref<SharedFile[]>([])
const breadcrumbs = ref<Array<{ id: number; name: string }>>([])
const loading = ref(false)

// フォルダ作成ダイアログ
const showNewFolderDialog = ref(false)
const newFolderName = ref('')
const creating = ref(false)

// ===== フォルダ読み込み =====

async function loadFolder(folderId: number | null) {
  loading.value = true
  try {
    if (folderId !== null) {
      const res = await getFolder(folderId)
      folders.value = res.data.subfolders
      files.value = res.data.files
      breadcrumbs.value = res.data.breadcrumbs
      currentFolderId.value = folderId
    }
    else {
      await loadRootFolders()
    }
  }
  catch {
    showError(t('tournament.files.load_error'))
  }
  finally {
    loading.value = false
  }
}

async function loadRootFolders() {
  currentFolderId.value = null
  files.value = []
  breadcrumbs.value = []

  if (activeTab.value === 'tournament') {
    const res = await listFolders(props.tournamentId)
    folders.value = res.data
  }
  else {
    const divId = activeTab.value as number
    const res = await listDivisionFolders(props.tournamentId, divId)
    folders.value = res.data
  }
}

// タブ切替時にルートへリセット
async function onTabChange(tab: TabKey) {
  activeTab.value = tab
  currentFolderId.value = null
  folders.value = []
  files.value = []
  breadcrumbs.value = []
  await loadFolder(null)
}

// ===== ファイル操作 =====

async function onDownload(file: SharedFile) {
  try {
    const res = await getDownloadUrl(file.id)
    window.open(res.data.downloadUrl, '_blank')
  }
  catch {
    showError(t('error.network'))
  }
}

async function onDeleteFile(file: SharedFile) {
  try {
    await deleteFile(file.id)
    files.value = files.value.filter(f => f.id !== file.id)
    showSuccess(t('common.deleted'))
  }
  catch {
    showError(t('error.network'))
  }
}

// ===== フォルダ作成 =====

async function onCreateFolder() {
  const name = newFolderName.value.trim()
  if (!name) return
  creating.value = true
  try {
    if (activeTab.value === 'tournament') {
      await createFolder(props.tournamentId, name, currentFolderId.value ?? undefined)
    }
    else {
      const divId = activeTab.value as number
      await createDivisionFolder(
        props.tournamentId,
        divId,
        name,
        currentFolderId.value ?? undefined,
      )
    }
    showSuccess(t('tournament.files.create_success'))
    showNewFolderDialog.value = false
    newFolderName.value = ''
    await loadFolder(currentFolderId.value)
  }
  catch {
    showError(t('tournament.files.create_error'))
  }
  finally {
    creating.value = false
  }
}

// ===== ユーティリティ =====

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
    <!-- タブ: 大会全体 / ディビジョン別 -->
    <div class="mb-4 flex flex-wrap gap-2">
      <button
        class="rounded-full px-4 py-1.5 text-sm font-medium transition-colors"
        :class="
          activeTab === 'tournament'
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200'
        "
        @click="onTabChange('tournament')"
      >
        {{ $t('tournament.files.tournament_folder') }}
      </button>
      <button
        v-for="div in divisions"
        :key="div.id"
        class="rounded-full px-4 py-1.5 text-sm font-medium transition-colors"
        :class="
          activeTab === div.id
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200'
        "
        @click="onTabChange(div.id)"
      >
        {{ div.name ?? `${$t('tournament.files.division_folder')} ${div.id}` }}
      </button>
    </div>

    <!-- ブレッドクラム -->
    <div class="mb-3 flex items-center gap-2 text-sm">
      <button class="text-primary hover:underline" @click="loadFolder(null)">
        {{ $t('tournament.files.back_to_root') }}
      </button>
      <template v-for="bc in breadcrumbs" :key="bc.id">
        <i class="pi pi-chevron-right text-xs text-surface-400" />
        <button class="text-primary hover:underline" @click="loadFolder(bc.id)">
          {{ bc.name }}
        </button>
      </template>
    </div>

    <!-- アクションバー -->
    <div class="mb-4 flex items-center gap-2">
      <Button
        v-if="isAdmin"
        :label="$t('tournament.files.create_folder')"
        icon="pi pi-folder-plus"
        text
        size="small"
        @click="showNewFolderDialog = true"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <!-- コンテンツ -->
    <div v-else class="flex flex-col gap-1">
      <!-- フォルダ一覧 -->
      <button
        v-for="folder in folders"
        :key="`f-${folder.id}`"
        class="flex items-center gap-3 rounded-lg px-4 py-3 text-left transition-colors hover:bg-surface-100"
        @click="loadFolder(folder.id)"
      >
        <i class="pi pi-folder text-xl text-amber-500" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">{{ folder.name }}</p>
          <p class="text-xs text-surface-400">{{ folder.fileCount }}</p>
        </div>
      </button>

      <!-- ファイル一覧 -->
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
            <span v-if="file.uploadedBy">{{ file.uploadedBy.displayName }}</span>
            <span>{{ relativeTime(file.createdAt) }}</span>
            <span v-if="file.versionCount > 1">v{{ file.versionCount }}</span>
          </div>
        </div>
        <div class="flex items-center gap-1">
          <Button
            icon="pi pi-download"
            text
            rounded
            size="small"
            :aria-label="$t('common.download')"
            @click="onDownload(file)"
          />
          <Button
            v-if="isAdmin"
            icon="pi pi-trash"
            text
            rounded
            size="small"
            severity="danger"
            :aria-label="$t('common.delete')"
            @click="onDeleteFile(file)"
          />
        </div>
      </div>

      <!-- 空状態 -->
      <div
        v-if="folders.length === 0 && files.length === 0"
        class="py-12 text-center"
      >
        <i class="pi pi-folder-open mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">{{ $t('tournament.files.empty') }}</p>
      </div>
    </div>

    <!-- フォルダ作成ダイアログ -->
    <Dialog
      v-model:visible="showNewFolderDialog"
      :header="$t('tournament.files.create_dialog_header')"
      modal
      class="w-full max-w-sm"
    >
      <InputText
        v-model="newFolderName"
        class="w-full"
        :placeholder="$t('tournament.files.folder_name_placeholder')"
        @keyup.enter="onCreateFolder"
      />
      <template #footer>
        <Button
          :label="$t('common.cancel')"
          text
          :disabled="creating"
          @click="showNewFolderDialog = false"
        />
        <Button
          :label="$t('tournament.files.create_folder')"
          :loading="creating"
          :disabled="!newFolderName.trim()"
          @click="onCreateFolder"
        />
      </template>
    </Dialog>
  </div>
</template>
