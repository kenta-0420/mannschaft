<script setup lang="ts">
import { marked } from 'marked'
import { sanitizeHtml } from '~/utils/sanitizeHtml'
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
const loadError = shallowRef<unknown>(null)
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
  loadError.value = null
  currentPage.value = page
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
  } catch (error) {
    loadError.value = error
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

/** HTML/Markdownの装飾を除去し、文字参照を復元して先頭100文字だけを表示する。 */
function bodyPreview(body: string): string {
  const html = marked.parse(body, { async: false })
    .replace(/<\/(?:p|li|h[1-6]|blockquote|pre|tr)>|<br\s*\/?\s*>/gi, '$& ')
  // template内は非活性なので、プレビュー用の画像等を取得せずテキストだけ取り出す。
  const template = document.createElement('template')
  template.innerHTML = sanitizeHtml(html)
  template.content.querySelectorAll('script, style').forEach(element => element.remove())
  const plainText = (template.content.textContent ?? '').replace(/\s+/g, ' ').trim()
  return Array.from(plainText).slice(0, 100).join('')
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
      <InputText v-model="searchQuery" :placeholder="t('bulletin.list.searchPlaceholder')" class="w-full min-w-0 text-base sm:w-48" />
      <Select
        v-model="selectedCategoryId"
        :options="[{ id: undefined, name: t('bulletin.list.allCategories') }, ...categories]"
        option-label="name"
        option-value="id"
        :placeholder="t('bulletin.list.category')"
        class="w-full min-w-0 sm:w-40"
      />
      <div class="ml-auto flex flex-wrap items-center gap-2">
        <Button :label="t('bulletin.list.readAll')" text size="small" class="min-h-11" @click="onReadAll" />
        <Button v-if="props.canCreate !== false" :label="t('bulletin.list.newThread')" icon="pi pi-plus" class="min-h-11" @click="emit('create')" />
      </div>
    </div>

    <!-- スレッド一覧 -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="loadThreads(currentPage)" />
    <DashboardEmptyState v-else-if="threads.length === 0" icon="pi pi-clipboard" :message="t('bulletin.list.empty')" />
    <div v-else data-testid="bulletin-thread-grid" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
      <SectionCard
        v-for="thread in threads"
        :key="thread.id"
        data-testid="bulletin-thread-card"
        class="group flex min-w-0 flex-col gap-3 hover:shadow-md"
        :class="!thread.isRead ? 'border-l-4 border-l-primary' : ''"
      >
        <!-- ピン -->
        <i v-if="thread.isPinned" class="pi pi-thumbtack mt-1 text-amber-500" />

        <button
          type="button"
          data-testid="bulletin-thread-select"
          class="min-h-11 min-w-11 flex-1 text-left"
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

          <h3 class="break-words text-base font-semibold [overflow-wrap:anywhere]" :class="!thread.isRead ? 'font-bold' : ''">
            {{ thread.title }}
          </h3>

          <p data-testid="bulletin-thread-preview" class="mt-2 break-words text-sm leading-relaxed text-surface-600 [overflow-wrap:anywhere] dark:text-surface-300">{{ bodyPreview(thread.body) }}</p>
          <div class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-400">
            <span>{{ thread.author.displayName || t('bulletin.unknownAuthor') }}</span>
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
          class="min-h-11 min-w-11 self-end"
          @click.stop="onArchive(thread.id)"
        />
      </SectionCard>
    </div>

    <!-- ページネーション -->
    <div v-if="!loadError && totalPages > 1" class="mt-4 flex justify-center">
      <Paginator :rows="20" :total-records="totalPages * 20" :first="currentPage * 20" @page="(e: { page: number }) => loadThreads(e.page)" />
    </div>
  </div>
</template>
