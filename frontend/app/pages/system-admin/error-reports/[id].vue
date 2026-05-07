<script setup lang="ts">
import type {
  AiAnalysisResponse,
  ErrorReportDetail,
  TimelineItem,
  WorkflowStage,
} from '~/types/error-report'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const {
  get,
  updateWorkflowStage,
  assign,
  addComment,
  fetchTimeline,
  reanalyze,
  fetchAiAnalyses,
} = useErrorReportAdmin()
const { error: showError, success: showSuccess } = useNotification()

const reportId = computed(() => Number(route.params.id))

const report = ref<ErrorReportDetail | null>(null)
const loading = ref(false)
const updating = ref(false)
const activeTab = ref('summary')

const timelineItems = ref<TimelineItem[]>([])
const timelineLoading = ref(false)
const timelineHasMore = ref(false)
const timelineCursor = ref<string | null>(null)

// F12.5 Phase 2-C — AI 分析関連状態
const latestAiAnalysis = ref<AiAnalysisResponse | null>(null)
const aiHistory = ref<AiAnalysisResponse[]>([])
const aiHistoryHasMore = ref(false)
const aiHistoryPage = ref(0)
const aiHistoryLoading = ref(false)
const aiReanalyzing = ref(false)

async function loadReport() {
  loading.value = true
  try {
    const res = await get(reportId.value)
    report.value = res.data
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.load_failed'))
  } finally {
    loading.value = false
  }
}

async function loadTimeline(append = false) {
  timelineLoading.value = true
  try {
    const cursor = append ? timelineCursor.value ?? undefined : undefined
    const res = await fetchTimeline(reportId.value, cursor)
    if (append) {
      timelineItems.value.push(...res.data.items)
    } else {
      timelineItems.value = res.data.items
    }
    timelineHasMore.value = res.data.hasMore
    timelineCursor.value = res.data.nextCursor
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.load_failed'))
  } finally {
    timelineLoading.value = false
  }
}

async function onWorkflowChange(stage: WorkflowStage | null) {
  if (!report.value) return
  updating.value = true
  try {
    const res = await updateWorkflowStage(reportId.value, stage)
    report.value = res.data
    showSuccess(t('error_report.messages.updated'))
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.invalid_transition'))
  } finally {
    updating.value = false
  }
}

async function onAssigneeChange(assigneeId: number | null) {
  if (!report.value) return
  updating.value = true
  try {
    const res = await assign(reportId.value, assigneeId)
    report.value = res.data
    if (assigneeId === null) {
      showSuccess(t('error_report.messages.unassigned'))
    } else {
      const name = res.data.assigneeName ?? `#${assigneeId}`
      showSuccess(t('error_report.messages.assigned', { name }))
    }
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.update_failed'))
  } finally {
    updating.value = false
  }
}

async function onCommentSubmit(content: string) {
  updating.value = true
  try {
    await addComment(reportId.value, content)
    showSuccess(t('error_report.messages.comment_added'))
    if (activeTab.value === 'timeline') {
      await loadTimeline(false)
    }
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.update_failed'))
  } finally {
    updating.value = false
  }
}

// F12.5 Phase 2-C — AI 分析タブが開かれたときに履歴を読み込む
async function loadAiHistory(append = false) {
  aiHistoryLoading.value = true
  try {
    const page = append ? aiHistoryPage.value + 1 : 0
    const res = await fetchAiAnalyses(reportId.value, page, 20)
    if (append) {
      aiHistory.value.push(...res.data)
    } else {
      aiHistory.value = res.data
      // 最新 SUCCESS を latest に
      const success = res.data.find((a) => a.status === 'SUCCESS')
      if (success) {
        latestAiAnalysis.value = success
      }
    }
    aiHistoryPage.value = page
    aiHistoryHasMore.value = page + 1 < res.meta.totalPages
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.load_failed'))
  } finally {
    aiHistoryLoading.value = false
  }
}

async function onReanalyze() {
  aiReanalyzing.value = true
  try {
    const res = await reanalyze(reportId.value)
    latestAiAnalysis.value = res.data
    aiHistory.value = [res.data, ...aiHistory.value]
    showSuccess(t('error_report.messages.ai_completed'))
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.update_failed'))
  } finally {
    aiReanalyzing.value = false
  }
}

watch(activeTab, (val) => {
  if (val === 'timeline' && timelineItems.value.length === 0) {
    void loadTimeline(false)
  }
  if (val === 'ai' && aiHistory.value.length === 0) {
    void loadAiHistory(false)
  }
})

onMounted(() => {
  void loadReport()
})

// レポート読み込み完了後に latest を初期化
watch(report, (val) => {
  if (val?.latestAiAnalysis) {
    // ErrorReportResponse の summary 型 → AiAnalysisResponse 型への簡易マッピング
    const s = val.latestAiAnalysis
    latestAiAnalysis.value = {
      id: s.id,
      errorReportId: val.id,
      modelName: '',
      promptTokens: 0,
      completionTokens: 0,
      estimatedCause: s.estimatedCause,
      fixProposal: s.fixProposal,
      impactAssessment: s.impactAssessment,
      suggestedFiles: s.suggestedFiles
        ? s.suggestedFiles.split(',').map((f) => f.trim()).filter((f) => f.length > 0)
        : [],
      status: 'SUCCESS',
      errorMessage: null,
      createdBy: null,
      createdByName: null,
      createdAt: s.createdAt,
    }
  }
})
</script>

<template>
  <div class="container mx-auto max-w-5xl space-y-4 p-4">
    <header class="flex items-center justify-between">
      <Button
        :label="t('error_report.actions.back')"
        icon="pi pi-arrow-left"
        text
        size="small"
        @click="router.push('/system-admin/error-reports')"
      />
    </header>

    <div v-if="loading" class="py-12 text-center text-sm text-surface-500">
      <i class="pi pi-spin pi-spinner mr-2" aria-hidden="true" />読み込み中...
    </div>

    <template v-else-if="report">
      <ErrorReportSummaryCard :report="report" />

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="summary">{{ t('error_report.detail.summary') }}</Tab>
          <Tab value="timeline">{{ t('error_report.detail.timeline') }}</Tab>
          <Tab value="ai">{{ t('error_report.detail.ai') }}</Tab>
          <Tab value="github">{{ t('error_report.detail.github') }}</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="summary">
            <div class="space-y-4">
              <div
                class="rounded-xl border border-surface-300 bg-surface-0 p-5 dark:border-surface-600 dark:bg-surface-800"
              >
                <ErrorReportWorkflowProgress
                  :status="report.status"
                  :workflow-stage="report.workflowStage"
                  :loading="updating"
                  @change="onWorkflowChange"
                />
              </div>

              <div
                class="rounded-xl border border-surface-300 bg-surface-0 p-5 dark:border-surface-600 dark:bg-surface-800"
              >
                <ErrorReportAssigneeSelector
                  :assignee-id="report.assigneeId"
                  :assignee-name="report.assigneeName"
                  :loading="updating"
                  @change="onAssigneeChange"
                />
              </div>

              <ErrorReportCommentForm :loading="updating" @submit="onCommentSubmit" />

              <details
                v-if="report.stackTrace"
                class="rounded-xl border border-surface-300 bg-surface-0 p-5 dark:border-surface-600 dark:bg-surface-800"
              >
                <summary class="cursor-pointer text-sm font-semibold">
                  {{ t('error_report.detail.stack_trace') }}
                </summary>
                <pre
                  class="mt-2 overflow-x-auto whitespace-pre-wrap text-xs"
                >{{ report.stackTrace }}</pre>
              </details>
            </div>
          </TabPanel>
          <TabPanel value="timeline">
            <ErrorReportTimelineView
              :items="timelineItems"
              :loading="timelineLoading"
              :has-more="timelineHasMore"
              @load-more="loadTimeline(true)"
            />
          </TabPanel>
          <TabPanel value="ai">
            <div class="space-y-4">
              <ErrorReportAiAnalysisCard
                :analysis="latestAiAnalysis"
                :loading="aiReanalyzing"
                @reanalyze="onReanalyze"
              />
              <ErrorReportAiAnalysisHistory
                :items="aiHistory"
                :loading="aiHistoryLoading"
                :has-more="aiHistoryHasMore"
                @load-more="loadAiHistory(true)"
              />
            </div>
          </TabPanel>
          <TabPanel value="github">
            <div
              class="rounded-xl border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-600"
            >
              {{ t('error_report.tabs.coming_soon') }}
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
