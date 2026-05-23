<script setup lang="ts">
import type {
  ErrorReportDetail,
  ErrorReportSeverity,
  ErrorReportStatus,
  KanbanColumn,
  KanbanStageKey,
  WorkflowStage,
} from '~/types/error-report'
import type { PageMeta } from '~/types/api'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { list, fetchKanban, updateWorkflowStage } = useErrorReportAdmin()
const { error: showError, success: showSuccess } = useNotification()
const router = useRouter()

interface FilterState {
  status: ErrorReportStatus | undefined
  severity: ErrorReportSeverity | undefined
  keyword: string
  from: string
  to: string
  overdueOnly: boolean
}

const filters = ref<FilterState>({
  status: undefined,
  severity: undefined,
  keyword: '',
  from: '',
  to: '',
  overdueOnly: false,
})

const reports = ref<ErrorReportDetail[]>([])
const meta = ref<PageMeta | null>(null)
const loading = ref(false)
const activeTab = ref('list')

const currentPage = ref(0)
const pageSize = ref(20)

// F12.5 Phase 2-E — Kanban 状態
const kanbanColumns = ref<KanbanColumn[]>([])
const kanbanLoading = ref(false)
const kanbanUpdating = ref(false)

/**
 * モバイル端末判定（768px 未満）。SSR 安全のため初期値は false、
 * onMounted 後に matchMedia で更新する。
 */
const isMobile = ref(false)

onMounted(() => {
  if (typeof window !== 'undefined') {
    const mql = window.matchMedia('(max-width: 768px)')
    isMobile.value = mql.matches
    mql.addEventListener('change', (e) => {
      isMobile.value = e.matches
      // モバイルになったら Kanban タブを離脱（list へ戻す）
      if (e.matches && activeTab.value === 'kanban') {
        activeTab.value = 'list'
      }
    })
  }
})

async function load() {
  loading.value = true
  try {
    const res = await list({
      status: filters.value.status,
      severity: filters.value.severity,
      from: filters.value.from || undefined,
      to: filters.value.to || undefined,
      page: currentPage.value,
      size: pageSize.value,
      sort: 'lastOccurredAt,desc',
    })
    reports.value = res.data
    meta.value = res.meta
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.load_failed'))
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  currentPage.value = 0
  void load()
}

function clearFilters() {
  filters.value = {
    status: undefined,
    severity: undefined,
    keyword: '',
    from: '',
    to: '',
    overdueOnly: false,
  }
  currentPage.value = 0
  void load()
}

function onPageChange(page: number, size: number) {
  currentPage.value = page
  pageSize.value = size
  void load()
}

function openDetail(id: number) {
  void router.push(`/system-admin/error-reports/${id}`)
}

// ===== F12.5 Phase 2-E — Kanban =====

async function loadKanban() {
  kanbanLoading.value = true
  try {
    const res = await fetchKanban()
    kanbanColumns.value = res.data.columns
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.load_failed'))
  } finally {
    kanbanLoading.value = false
  }
}

/**
 * Kanban カードをカラム間で移動した際の楽観的更新ハンドラ。
 * 失敗時は元のカラムへロールバック + エラートースト。
 */
async function onKanbanStageChange(cardId: number, newStage: WorkflowStage | null) {
  // ロールバック用に直前の状態を保持
  const snapshot: KanbanColumn[] = kanbanColumns.value.map((c) => ({
    ...c,
    cards: [...c.cards],
  }))

  kanbanUpdating.value = true
  try {
    await updateWorkflowStage(cardId, newStage)
    showSuccess(t('error_report.messages.updated'))
    // 整合性のため再読み込み（totalCount / status 自動遷移を反映）
    await loadKanban()
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.invalid_transition'))
    // ロールバック
    kanbanColumns.value = snapshot
  } finally {
    kanbanUpdating.value = false
  }
}

/**
 * 「もっと表示」リンク押下時、リストタブに切り替え + workflow_stage フィルタ
 * は将来対応とする（現状はリストタブへ遷移するだけ）。
 */
function onKanbanLoadMore(_stageKey: KanbanStageKey) {
  activeTab.value = 'list'
}

watch(activeTab, (val) => {
  if (val === 'kanban' && kanbanColumns.value.length === 0) {
    void loadKanban()
  }
})

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="container mx-auto max-w-7xl space-y-4 p-4">
    <header class="flex items-center justify-between">
      <h1 class="flex items-center gap-2 text-xl font-bold">
        <i class="pi pi-exclamation-triangle" aria-hidden="true" />
        {{ t('error_report.title') }}
      </h1>
    </header>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab value="list">{{ t('error_report.tabs.list') }}</Tab>
        <Tab v-if="!isMobile" value="kanban">{{ t('error_report.tabs.kanban') }}</Tab>
        <Tab value="stats">{{ t('error_report.tabs.stats') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="list">
          <div class="space-y-4">
            <ErrorReportFilterBar
              v-model="filters"
              @apply="applyFilters"
              @clear="clearFilters"
            />
            <ErrorReportListTable
              :reports="reports"
              :loading="loading"
              :meta="meta"
              @open="openDetail"
              @page-change="onPageChange"
            />
          </div>
        </TabPanel>
        <TabPanel v-if="!isMobile" value="kanban">
          <div class="space-y-3">
            <div v-if="kanbanLoading" class="py-12 text-center text-sm text-surface-500">
              <i class="pi pi-spin pi-spinner mr-2" aria-hidden="true" />
              {{ t('error_report.kanban.title') }}
            </div>
            <ErrorReportKanbanBoard
              v-else
              :columns="kanbanColumns"
              :loading="kanbanUpdating"
              @stage-changed="onKanbanStageChange"
              @load-more="onKanbanLoadMore"
              @open="openDetail"
            />
          </div>
        </TabPanel>
        <TabPanel value="stats">
          <div class="rounded-xl border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-600">
            {{ t('error_report.tabs.coming_soon') }}
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>
