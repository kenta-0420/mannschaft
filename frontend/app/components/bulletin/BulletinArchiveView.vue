<script setup lang="ts">
import type {
  ArchiveFolderTreeNode,
  BulletinArchiveFolder,
  BulletinScopeType,
  BulletinThreadResponse,
} from '~/types/bulletin'

/**
 * 掲示板 保管庫ビュー（設計書 F05.1 §5）。
 *
 * 2ペイン構成:
 *   - サイドバー: フォルダツリー（未分類を先頭固定・threadCount バッジ）
 *   - メイン: 選択フォルダの保管庫スレッド一覧 + パンくず
 *
 * 権限出し分け（canManage = ADMIN / DEPUTY_ADMIN）:
 *   - 「+ フォルダ作成」「編集」「削除」「フォルダへ移動」「保管庫から戻す」
 *   - MEMBER / SUPPORTER は閲覧のみ
 */
interface Props {
  scopeType: BulletinScopeType
  scopeId: number
  canManage?: boolean
}

const props = withDefaults(defineProps<Props>(), { canManage: false })

const emit = defineEmits<{
  /** スレッド選択（親で詳細表示）。 */
  select: [thread: BulletinThreadResponse]
}>()

const { t } = useI18n()
const { showError, showSuccess } = useNotification()
const confirm = useConfirm()
const { relativeTime } = useRelativeTime()
const {
  getArchiveFolderTree,
  deleteArchiveFolder,
  getArchiveThreads,
  moveThreadToFolder,
  archiveScopedThread,
} = useBulletinApi()

// === ツリー state ===
const tree = ref<ArchiveFolderTreeNode[]>([])
const unfiledThreadCount = ref(0)
const treeLoading = ref(false)

// === スレッド一覧 state ===
/** 選択フォルダ: null=未分類 / 'all'=全件 / UUID。 */
const selectedFolderId = ref<string | null>(null)
const threads = ref<BulletinThreadResponse[]>([])
const threadsLoading = ref(false)
const totalPages = ref(0)
const currentPage = ref(0)

// === 編集ダイアログ state ===
const showEditDialog = ref(false)
const editTarget = ref<BulletinArchiveFolder | null>(null)
const editParentId = ref<string | null>(null)

// === フォルダへ移動 state ===
const showMoveDialog = ref(false)
const movingThread = ref<BulletinThreadResponse | null>(null)
const moveTargetId = ref<string | null>(null)

/** フラット化したフォルダ一覧（移動先 Select 用。インデント付きラベル）。 */
const flatFolders = computed(() => {
  const out: { id: string | null; label: string }[] = [
    { id: null, label: t('bulletin.archive.unfiled') },
  ]
  const walk = (nodes: ArchiveFolderTreeNode[], depth: number) => {
    for (const node of nodes) {
      out.push({ id: node.id, label: `${'　'.repeat(depth)}${node.name}` })
      if (node.children?.length) walk(node.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

/** パンくず: 選択フォルダまでのパス（未分類/全件はトップのみ）。 */
const breadcrumbs = computed<string[]>(() => {
  const root = t('bulletin.archive.title')
  if (selectedFolderId.value === 'all') return [root, t('bulletin.archive.allArchived')]
  if (selectedFolderId.value === null) return [root, t('bulletin.archive.unfiled')]
  const path: string[] = []
  const find = (nodes: ArchiveFolderTreeNode[], trail: ArchiveFolderTreeNode[]): boolean => {
    for (const node of nodes) {
      const nextTrail = [...trail, node]
      if (node.id === selectedFolderId.value) {
        path.push(...nextTrail.map(n => n.name))
        return true
      }
      if (node.children?.length && find(node.children, nextTrail)) return true
    }
    return false
  }
  find(tree.value, [])
  return [root, ...path]
})

async function loadTree() {
  treeLoading.value = true
  try {
    const res = await getArchiveFolderTree(props.scopeType, props.scopeId)
    tree.value = res.data
    unfiledThreadCount.value = res.meta.unfiledThreadCount
  }
  catch {
    showError(t('bulletin.archive.loadTreeFailed'))
  }
  finally {
    treeLoading.value = false
  }
}

async function loadThreads(page = 0) {
  threadsLoading.value = true
  try {
    const res = await getArchiveThreads(props.scopeType, props.scopeId, {
      folderId: selectedFolderId.value,
      page,
    })
    threads.value = res.data
    totalPages.value = res.meta.totalPages
    currentPage.value = res.meta.page
  }
  catch {
    showError(t('bulletin.archive.loadThreadsFailed'))
  }
  finally {
    threadsLoading.value = false
  }
}

function onSelectFolder(folderId: string | null) {
  selectedFolderId.value = folderId
  loadThreads(0)
}

// === フォルダ CRUD ===
function openCreateRoot() {
  editTarget.value = null
  editParentId.value = null
  showEditDialog.value = true
}

function openCreateChild(parentFolderId: string | null) {
  editTarget.value = null
  editParentId.value = parentFolderId
  showEditDialog.value = true
}

function openEdit(folder: ArchiveFolderTreeNode) {
  editTarget.value = folder
  editParentId.value = folder.parentId
  showEditDialog.value = true
}

function confirmDelete(folder: ArchiveFolderTreeNode) {
  confirm.require({
    message: t('bulletin.archive.deleteConfirm'),
    header: t('bulletin.archive.deleteFolder'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    acceptLabel: t('bulletin.archive.delete'),
    rejectLabel: t('bulletin.archive.cancel'),
    accept: () => doDelete(folder),
  })
}

async function doDelete(folder: ArchiveFolderTreeNode) {
  try {
    const res = await deleteArchiveFolder(props.scopeType, props.scopeId, folder.id)
    showSuccess(res.data.message || t('bulletin.archive.deleted'))
    // 削除フォルダを選択中だった場合は未分類へフォールバック
    if (selectedFolderId.value === folder.id) {
      selectedFolderId.value = null
    }
    await loadTree()
    await loadThreads(currentPage.value)
  }
  catch {
    showError(t('bulletin.archive.deleteFailed'))
  }
}

async function onFolderSaved() {
  await loadTree()
}

// === スレッド操作 ===
function openMove(thread: BulletinThreadResponse) {
  movingThread.value = thread
  moveTargetId.value = thread.archiveFolderId ?? null
  showMoveDialog.value = true
}

async function confirmMove() {
  if (!movingThread.value) return
  try {
    await moveThreadToFolder(
      props.scopeType,
      props.scopeId,
      movingThread.value.id,
      moveTargetId.value,
    )
    showSuccess(t('bulletin.archive.moved'))
    showMoveDialog.value = false
    movingThread.value = null
    await loadTree()
    await loadThreads(currentPage.value)
  }
  catch {
    showError(t('bulletin.archive.moveFailed'))
  }
}

function confirmUnarchive(thread: BulletinThreadResponse) {
  confirm.require({
    message: t('bulletin.archive.unarchiveConfirm'),
    header: t('bulletin.archive.unarchive'),
    icon: 'pi pi-undo',
    acceptLabel: t('bulletin.archive.unarchive'),
    rejectLabel: t('bulletin.archive.cancel'),
    accept: () => doUnarchive(thread),
  })
}

async function doUnarchive(thread: BulletinThreadResponse) {
  try {
    await archiveScopedThread(props.scopeType, props.scopeId, thread.id, false)
    showSuccess(t('bulletin.archive.unarchived'))
    await loadTree()
    await loadThreads(currentPage.value)
  }
  catch {
    showError(t('bulletin.archive.unarchiveFailed'))
  }
}

function getPriorityClass(priority: string): string {
  switch (priority) {
    case 'CRITICAL': return 'bg-red-100 text-red-700'
    case 'IMPORTANT': return 'bg-orange-100 text-orange-700'
    case 'WARNING': return 'bg-yellow-100 text-yellow-700'
    case 'LOW': return 'bg-surface-100 text-surface-500'
    default: return 'bg-blue-100 text-blue-700'
  }
}

function getPriorityLabel(priority: string): string {
  return t(`bulletin.archive.priority.${priority}`)
}

onMounted(() => {
  loadTree()
  loadThreads()
})

defineExpose({ refresh: () => { loadTree(); loadThreads(currentPage.value) } })
</script>

<template>
  <div class="flex flex-col gap-4 md:flex-row md:items-start">
    <!-- サイドバー: フォルダツリー -->
    <aside class="w-full shrink-0 rounded-xl border border-surface-200 bg-surface-0 p-3 md:w-64 dark:border-surface-700">
      <div class="mb-2 flex items-center justify-between">
        <h2 class="text-sm font-semibold text-surface-600 dark:text-surface-300">
          {{ $t('bulletin.archive.folders') }}
        </h2>
        <Button
          v-if="props.canManage"
          icon="pi pi-plus"
          :label="$t('bulletin.archive.createFolder')"
          text
          size="small"
          @click="openCreateRoot"
        />
      </div>

      <div v-if="treeLoading" class="flex justify-center py-6">
        <LoadingBounce />
      </div>
      <BulletinArchiveFolderTree
        v-else
        :nodes="tree"
        :selected-id="selectedFolderId"
        :unfiled-thread-count="unfiledThreadCount"
        :can-manage="props.canManage"
        @select="onSelectFolder"
        @edit="openEdit"
        @remove="confirmDelete"
        @add-child="openCreateChild"
      />
    </aside>

    <!-- メイン: スレッド一覧 -->
    <section class="min-w-0 flex-1">
      <!-- パンくず -->
      <nav class="mb-3 flex flex-wrap items-center gap-1 text-sm text-surface-500" :aria-label="$t('bulletin.archive.breadcrumb')">
        <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
          <i v-if="idx > 0" class="pi pi-angle-right text-xs" />
          <span :class="idx === breadcrumbs.length - 1 ? 'font-semibold text-surface-700 dark:text-surface-200' : ''">
            {{ crumb }}
          </span>
        </template>
      </nav>

      <div v-if="threadsLoading" class="flex justify-center py-8">
        <LoadingBounce />
      </div>

      <div v-else class="flex flex-col gap-2">
        <div
          v-for="thread in threads"
          :key="thread.id"
          class="flex items-start gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-700"
        >
          <button
            type="button"
            class="min-w-0 flex-1 text-left"
            @click="emit('select', thread)"
          >
            <div class="mb-1 flex flex-wrap items-center gap-2">
              <span :class="getPriorityClass(thread.priority)" class="rounded px-1.5 py-0.5 text-xs font-medium">
                {{ getPriorityLabel(thread.priority) }}
              </span>
              <span v-if="thread.categoryName" class="rounded px-1.5 py-0.5 text-xs" :style="thread.categoryColor ? `background-color: ${thread.categoryColor}20; color: ${thread.categoryColor}` : ''">
                {{ thread.categoryName }}
              </span>
            </div>
            <h3 class="text-sm font-semibold">
              {{ thread.title }}
            </h3>
            <div class="mt-1 flex items-center gap-3 text-xs text-surface-400">
              <span>{{ thread.author.displayName }}</span>
              <span>{{ relativeTime(thread.createdAt) }}</span>
              <span v-if="thread.replyCount"><i class="pi pi-comment" /> {{ thread.replyCount }}</span>
            </div>
          </button>

          <!-- 管理操作（ADMIN/DEPUTY のみ） -->
          <div v-if="props.canManage" class="flex shrink-0 items-center gap-1">
            <Button
              icon="pi pi-folder"
              :label="$t('bulletin.archive.moveToFolder')"
              text
              size="small"
              @click="openMove(thread)"
            />
            <Button
              icon="pi pi-undo"
              :label="$t('bulletin.archive.unarchive')"
              text
              size="small"
              severity="secondary"
              @click="confirmUnarchive(thread)"
            />
          </div>
        </div>

        <DashboardEmptyState
          v-if="threads.length === 0"
          icon="pi pi-folder-open"
          :message="$t('bulletin.archive.emptyThreads')"
        />
      </div>

      <!-- ページネーション -->
      <div v-if="totalPages > 1" class="mt-4 flex justify-center">
        <Paginator
          :rows="20"
          :total-records="totalPages * 20"
          :first="currentPage * 20"
          @page="(e: { page: number }) => loadThreads(e.page)"
        />
      </div>
    </section>

    <!-- フォルダ作成・編集ダイアログ -->
    <BulletinArchiveFolderEditDialog
      v-model:visible="showEditDialog"
      :scope-type="props.scopeType"
      :scope-id="props.scopeId"
      :edit-target="editTarget"
      :parent-folder-id="editParentId"
      @saved="onFolderSaved"
    />

    <!-- フォルダへ移動ダイアログ -->
    <Dialog
      v-model:visible="showMoveDialog"
      :header="$t('bulletin.archive.moveToFolder')"
      :modal="true"
      :style="{ width: '380px' }"
    >
      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium">{{ $t('bulletin.archive.selectFolder') }}</label>
        <Select
          v-model="moveTargetId"
          :options="flatFolders"
          option-label="label"
          option-value="id"
          class="w-full"
        />
      </div>
      <template #footer>
        <Button :label="$t('bulletin.archive.cancel')" severity="secondary" outlined @click="showMoveDialog = false" />
        <Button :label="$t('bulletin.archive.move')" @click="confirmMove" />
      </template>
    </Dialog>
  </div>
</template>
