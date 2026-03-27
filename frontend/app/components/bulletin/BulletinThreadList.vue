<script setup lang="ts">
import type { BulletinThreadResponse, BulletinCategory, BulletinScopeType } from '~/types/bulletin'

const props = defineProps<{
  scopeType: BulletinScopeType
  scopeId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  select: [thread: BulletinThreadResponse]
  create: []
}>()

const { getThreads, getCategories, readAll } = useBulletinApi()
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
    showError('掲示板の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function onReadAll() {
  try {
    await readAll(props.scopeType, props.scopeId)
    threads.value.forEach(t => t.isRead = true)
    showSuccess('すべて既読にしました')
  } catch {
    showError('一括既読に失敗しました')
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
  const labels: Record<string, string> = { CRITICAL: '緊急', IMPORTANT: '重要', WARNING: '注意', INFO: '情報', LOW: '低' }
  return labels[priority] || priority
}

watch([selectedCategoryId, searchQuery], () => loadThreads())
onMounted(() => { loadCategories(); loadThreads() })

defineExpose({ refresh: () => loadThreads() })
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <InputText v-model="searchQuery" placeholder="検索..." class="w-48" />
      <Select
        v-model="selectedCategoryId"
        :options="[{ id: undefined, name: 'すべて' }, ...categories]"
        option-label="name"
        option-value="id"
        placeholder="カテゴリ"
        class="w-40"
      />
      <div class="ml-auto flex items-center gap-2">
        <Button label="一括既読" text size="small" @click="onReadAll" />
        <Button label="新規スレッド" icon="pi pi-plus" @click="emit('create')" />
      </div>
    </div>

    <!-- スレッド一覧 -->
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="flex flex-col gap-2">
      <button
        v-for="thread in threads"
        :key="thread.id"
        class="flex items-start gap-3 rounded-xl border border-surface-200 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm"
        :class="!thread.isRead ? 'border-l-4 border-l-primary' : ''"
        @click="emit('select', thread)"
      >
        <!-- ピン -->
        <i v-if="thread.isPinned" class="pi pi-thumbtack mt-1 text-amber-500" />

        <div class="min-w-0 flex-1">
          <div class="mb-1 flex flex-wrap items-center gap-2">
            <span :class="getPriorityClass(thread.priority)" class="rounded px-1.5 py-0.5 text-xs font-medium">
              {{ getPriorityLabel(thread.priority) }}
            </span>
            <span v-if="thread.categoryName" class="rounded px-1.5 py-0.5 text-xs" :style="thread.categoryColor ? `background-color: ${thread.categoryColor}20; color: ${thread.categoryColor}` : ''">
              {{ thread.categoryName }}
            </span>
            <span v-if="thread.isLocked" class="text-xs text-surface-400"><i class="pi pi-lock" /> ロック中</span>
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
        </div>
      </button>

      <div v-if="threads.length === 0" class="py-12 text-center">
        <i class="pi pi-clipboard mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">スレッドがありません</p>
      </div>
    </div>

    <!-- ページネーション -->
    <div v-if="totalPages > 1" class="mt-4 flex justify-center">
      <Paginator :rows="20" :total-records="totalPages * 20" :first="currentPage * 20" @page="(e: any) => loadThreads(e.page)" />
    </div>
  </div>
</template>
