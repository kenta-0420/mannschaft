<script setup lang="ts">
import type { QuickMemoResponse, TagResponse } from '~/types/quickMemo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const memoApi = useQuickMemoApi()
const tagApi = useTagApi()
const capture = useQuickMemoCapture()

capture.registerShortcut()

// ─── State ───────────────────────────────────────────────────────────────────

const activeTab = ref<'UNSORTED' | 'ARCHIVED' | 'CONVERTED'>('UNSORTED')
const memos = ref<QuickMemoResponse[]>([])
const personalTags = ref<TagResponse[]>([])
const loading = ref(false)
const page = ref(1)
const totalPages = ref(1)
const unsortedCount = ref(0)
const searchQuery = ref('')
const searchResults = ref<QuickMemoResponse[] | null>(null)
const captureVisible = computed({
  get: () => capture.visible.value,
  set: (v) => { if (!v) capture.close() },
})

const selectedMemoId = ref<number | null>(null)
const drawerVisible = ref(false)

// ─── データ取得 ────────────────────────────────────────────────────────────

async function loadMemos() {
  loading.value = true
  try {
    const res = await memoApi.listMemos({ status: activeTab.value, page: page.value, size: 20 })
    memos.value = res.data
    totalPages.value = res.meta.totalPages
    if (res.meta.unsortedCount !== undefined) unsortedCount.value = res.meta.unsortedCount
  } catch {
    notification.error(t('quick_memo.load_error'))
  } finally {
    loading.value = false
  }
}

async function loadTags() {
  try {
    const res = await tagApi.listTags('personal')
    personalTags.value = res.data
  } catch (e) {
    console.error('タグ読み込み失敗', e)
  }
}

onMounted(() => {
  loadMemos()
  loadTags()
})

watch(activeTab, () => {
  page.value = 1
  loadMemos()
})

// ─── メモ操作 ──────────────────────────────────────────────────────────────

async function handleArchive(id: number) {
  try {
    await memoApi.archiveMemo(id)
    notification.success(t('quick_memo.action.archived'))
    loadMemos()
  } catch {
    notification.error(t('quick_memo.action.archive_error'))
  }
}

async function handleRestore(id: number) {
  try {
    await memoApi.restoreMemo(id)
    notification.success(t('quick_memo.action.restored'))
    loadMemos()
  } catch {
    notification.error(t('quick_memo.action.restore_error'))
  }
}

async function handleDelete(id: number) {
  try {
    await memoApi.deleteMemo(id)
    notification.success(t('quick_memo.action.deleted'))
    loadMemos()
  } catch {
    notification.error(t('quick_memo.action.delete_error'))
  }
}

function openDetail(memo: QuickMemoResponse) {
  selectedMemoId.value = memo.id
  drawerVisible.value = true
}

// ─── 検索 ──────────────────────────────────────────────────────────────────

let searchTimer: ReturnType<typeof setTimeout> | null = null

watch(searchQuery, (q) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (!q.trim()) {
    searchResults.value = null
    return
  }
  searchTimer = setTimeout(async () => {
    try {
      const res = await memoApi.searchMemos(q.trim())
      searchResults.value = res.data
    } catch {
      searchResults.value = []
    }
  }, 300)
})

const displayedMemos = computed(() => searchResults.value ?? memos.value)

// ─── 上限バナー ────────────────────────────────────────────────────────────

const showWarningBanner = computed(
  () => unsortedCount.value >= 450 && unsortedCount.value < 500 && activeTab.value === 'UNSORTED',
)
const showLimitBanner = computed(
  () => unsortedCount.value >= 500 && activeTab.value === 'UNSORTED',
)
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-xl font-bold">{{ t('quick_memo.title') }}</h1>
      <div class="flex gap-2">
        <Button
          icon="pi pi-cog"
          rounded
          text
          :title="t('quick_memo.settings')"
          @click="$router.push('/quick-memos/settings')"
        />
        <Button
          icon="pi pi-trash"
          rounded
          text
          :title="t('quick_memo.trash.title')"
          @click="$router.push('/quick-memos/trash')"
        />
        <Button
          icon="pi pi-feather"
          :label="t('quick_memo.capture_modal.submit')"
          @click="capture.open()"
        />
      </div>
    </div>

    <!-- 上限警告バナー -->
    <Message v-if="showWarningBanner" severity="warn" class="mb-4" :closable="false">
      {{ t('quick_memo.limit_warning', { count: unsortedCount }) }}
    </Message>
    <Message v-if="showLimitBanner" severity="error" class="mb-4" :closable="false">
      {{ t('quick_memo.limit_exceeded') }}
    </Message>

    <!-- 検索バー -->
    <div class="mb-4">
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="searchQuery"
          :placeholder="t('quick_memo.search.placeholder')"
          class="w-full"
        />
      </IconField>
    </div>

    <!-- タブ -->
    <div class="mb-4 flex gap-1 rounded-lg bg-surface-100 p-1 dark:bg-surface-700">
      <button
        v-for="tab in (['UNSORTED', 'ARCHIVED', 'CONVERTED'] as const)"
        :key="tab"
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
        :class="
          activeTab === tab
            ? 'bg-surface-0 shadow-sm text-surface-800 dark:bg-surface-800 dark:text-surface-100'
            : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'
        "
        @click="activeTab = tab"
      >
        {{ t(`quick_memo.status.${tab}`) }}
        <span
          v-if="tab === 'UNSORTED' && unsortedCount > 0"
          class="ml-1 rounded-full bg-primary/10 px-1.5 py-0.5 text-xs text-primary"
        >
          {{ unsortedCount }}
        </span>
      </button>
    </div>

    <!-- メモ一覧 -->
    <div v-if="loading" class="space-y-3">
      <Skeleton v-for="i in 5" :key="i" height="80px" border-radius="12px" />
    </div>

    <div v-else-if="displayedMemos.length === 0" class="py-16 text-center text-surface-400">
      <i class="pi pi-feather mb-3 text-4xl" />
      <p>{{ t('quick_memo.no_memos') }}</p>
    </div>

    <div v-else class="space-y-3">
      <QuickMemoCard
        v-for="memo in displayedMemos"
        :key="memo.id"
        :memo="memo"
        @click="openDetail"
        @archive="handleArchive"
        @restore="handleRestore"
        @delete="handleDelete"
        @convert="(m) => openDetail(m)"
      />
    </div>

    <!-- ページネーション -->
    <div v-if="totalPages > 1 && !searchResults" class="mt-6 flex justify-center">
      <Paginator
        v-model:first="page"
        :rows="1"
        :total-records="totalPages"
        @page="(e) => { page = e.page + 1; loadMemos() }"
      />
    </div>
  </div>

  <!-- 入力モーダル -->
  <QuickMemoCaptureModal v-model:visible="captureVisible" @created="loadMemos" />

  <!-- 詳細ドロワー -->
  <QuickMemoDetailDrawer
    v-model:visible="drawerVisible"
    :memo-id="selectedMemoId"
    @updated="loadMemos"
    @archived="loadMemos"
    @deleted="loadMemos"
    @converted="loadMemos"
  />

  <!-- 常駐フローティングボタン（ページ内に入れないよう注意：layout側推奨だが暫定） -->
  <QuickMemoFloatingButton />
</template>
