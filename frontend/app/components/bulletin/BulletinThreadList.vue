<script setup lang="ts">
import type { BulletinThreadResponse, BulletinCategory, BulletinScopeType } from '~/types/bulletin'

const props = defineProps<{
  scopeType: BulletinScopeType
  /** TEAM/ORGANIZATION は数値ID、VILLAGE は UUID 文字列 */
  scopeId: string | number
  canManage?: boolean
  /** スレッド新規作成ボタンを表示するか（デフォルト true・後方互換） */
  canCreate?: boolean
}>()

const emit = defineEmits<{
  select: [thread: BulletinThreadResponse]
  create: []
}>()

const { t } = useI18n()
const { getThreads, getCategories, readAll, archiveScopedThread } = useBulletinApi()
const { showError, showSuccess } = useNotification()

const threads = ref<BulletinThreadResponse[]>([])
const categories = ref<BulletinCategory[]>([])
const loading = ref(false)
const selectedCategoryId = ref<number | undefined>(undefined)
const searchQuery = ref('')
const totalPages = ref(0)
const currentPage = ref(0)

const { relativeTime } = useRelativeTime()

async function loadCategories() {
  try {
    const res = await getCategories(props.scopeType, props.scopeId)
    categories.value = res.data
  } catch { /* silent */ }
}

async function loadThreads(page = 0) {
  loading.value = true
  try {
    const res = await getThreads({
      scopeType: props.scopeType,
      scopeId: props.scopeId,
      categoryId: selectedCategoryId.value,
      search: searchQuery.value || undefined,
      page,
    })
    threads.value = res.data
    totalPages.value = res.meta.totalPages
    currentPage.value = res.meta.page
  } catch {
    showError(t('bulletin.list.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function onReadAll() {
  try {
    await readAll(props.scopeType, props.scopeId)
    threads.value.forEach(t => t.isRead = true)
    showSuccess(t('bulletin.list.readAllDone'))
  } catch {
    showError(t('bulletin.list.readAllFailed'))
  }
}

/** スレッドを保管庫へ格納（ワンクリック・フォルダ指定は任意で後回し）。ADMIN/DEPUTY のみ。 */
async function onArchive(threadId: number) {
  try {
    await archiveScopedThread(props.scopeType, props.scopeId, threadId, true)
    showSuccess(t('bulletin.list.archivedDone'))
    await loadThreads(currentPage.value)
  } catch {
    showError(t('bulletin.list.archiveFailed'))
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

watch([selectedCategoryId, searchQuery], () => loadThreads())
onMounted(() => { loadCategories(); loadThreads() })

defineExpose({ refresh: () => loadThreads() })
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <InputText v-model="searchQuery" :placeholder="t('bulletin.list.searchPlaceholder')" class="w-48" />
      <Select
        v-model="selectedCategoryId"
        :options="[{ id: undefined, name: t('bulletin.list.allCategories') }, ...categories]"
        option-label="name"
        option-value="id"
        :placeholder="t('bulletin.list.category')"
        class="w-40"
      />
      <div class="ml-auto flex items-center gap-2">
        <Button :label="t('bulletin.list.readAll')" text size="small" @click="onReadAll" />
        <Button v-if="props.canCreate !== false" :label="t('bulletin.list.newThread')" icon="pi pi-plus" @click="emit('create')" />
      </div>
    </div>

    <!-- スレッド一覧 -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <div v-else class="flex flex-col gap-2">
      <div
        v-for="thread in threads"
        :key="thread.id"
        class="group flex items-start gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-sm"
        :class="!thread.isRead ? 'border-l-4 border-l-primary' : ''"
      >
        <!-- ピン -->
        <i v-if="thread.isPinned" class="pi pi-thumbtack mt-1 text-amber-500" />

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
            <span v-if="thread.isLocked" class="text-xs text-surface-400"><i class="pi pi-lock" /> {{ t('bulletin.list.locked') }}</span>
          </div>

          <h3 class="text-sm font-semibold" :class="!thread.isRead ? 'font-bold' : ''">
            {{ thread.title }}
          </h3>

          <div class="mt-1 flex items-center gap-3 text-xs text-surface-400">
            <span>{{ thread.author.displayName }}</span>
            <span>{{ relativeTime(thread.createdAt) }}</span>
            <span v-if="thread.replyCount"><i class="pi pi-comment" /> {{ thread.replyCount }}</span>
            <span v-if="thread.readTrackingMode !== 'NONE'"><i class="pi pi-eye" /> {{ thread.readCount }}</span>
          </div>
        </button>

        <!-- 保管庫へ（ADMIN/DEPUTY のみ・ワンクリック） -->
        <Button
          v-if="canManage"
          icon="pi pi-inbox"
          :label="t('bulletin.list.archiveAction')"
          text
          size="small"
          severity="secondary"
          class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
          @click.stop="onArchive(thread.id)"
        />
      </div>

      <DashboardEmptyState v-if="threads.length === 0" icon="pi pi-clipboard" :message="t('bulletin.list.empty')" />
    </div>

    <!-- ページネーション -->
    <div v-if="totalPages > 1" class="mt-4 flex justify-center">
      <Paginator :rows="20" :total-records="totalPages * 20" :first="currentPage * 20" @page="(e: { page: number }) => loadThreads(e.page)" />
    </div>
  </div>
</template>
