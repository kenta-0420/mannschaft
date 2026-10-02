<script setup lang="ts">
/**
 * F22.1 統合「要対応」ウィジェット（⑧）。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 * - 回覧板 / アンケート / 出席確認 の 3 区分を 1 枚に集約。
 * - ビューポート進入時に GET /dashboard/{scope}/{id}/action-required を遅延取得（02 §3.3）。
 * - 各ドメインの per-scope 認可は BE 側で必ず通る。FE は集計結果を表示するのみ。
 * - 各アイテムクリックでモーダルを開き、ページ遷移なしで回答・押印できる。
 */
import type {
  ScopeTabType,
  ActionRequiredSummary,
  CirculationActionItem,
  SurveyActionItem,
  AttendanceActionItem,
} from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
  scopeId: string
}>()

const { getActionRequired } = useScopeTabApi()

const summary = ref<ActionRequiredSummary | null>(null)
const loading = ref(false)
const errorKey = ref<string | null>(null)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

// === モーダル状態 ===
const circulationModal = ref<{ visible: boolean; item: CirculationActionItem | null }>({
  visible: false,
  item: null,
})
const surveyModal = ref<{ visible: boolean; item: SurveyActionItem | null }>({
  visible: false,
  item: null,
})
const attendanceModal = ref<{ visible: boolean; item: AttendanceActionItem | null }>({
  visible: false,
  item: null,
})

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  errorKey.value = null
  try {
    summary.value = await getActionRequired(props.scopeType, props.scopeId)
    loaded.value = true
  } catch (e) {
    // 握り潰さない。i18n キーを保持して UI に表示する。
    console.error('[ScopeActionRequiredWidget] fetch failed', e)
    errorKey.value = 'swipeWidgets.actionRequired.loadError'
  } finally {
    loading.value = false
  }
}

// scopeId が変わったら再取得対象に戻す。
watch(
  () => props.scopeId,
  () => {
    loaded.value = false
    summary.value = null
    if (rootEl.value) observeViewport()
  },
)

function observeViewport() {
  if (!import.meta.client || !rootEl.value) return
  observer?.disconnect()
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        fetchSummary()
        observer?.disconnect()
      }
    }
  })
  observer.observe(rootEl.value)
}

onMounted(() => {
  observeViewport()
})

onBeforeUnmount(() => {
  observer?.disconnect()
})

const isEmpty = computed(
  () => loaded.value && (summary.value?.totalActionCount ?? 0) === 0,
)

const surveyListPath = computed(() =>
  props.scopeType === 'TEAM'
    ? `/teams/${props.scopeId}/surveys`
    : `/organizations/${props.scopeId}/surveys`,
)

const circulationPath = computed(() =>
  props.scopeType === 'TEAM'
    ? `/teams/${props.scopeId}/circulation`
    : `/organizations/${props.scopeId}/circulation`,
)

const schedulePath = computed(() =>
  props.scopeType === 'TEAM'
    ? `/teams/${props.scopeId}/schedule`
    : `/organizations/${props.scopeId}/schedule`,
)

// === 件数減算 ===
function decrementCirculation() {
  if (!summary.value) return
  summary.value.totalActionCount = Math.max(0, summary.value.totalActionCount - 1)
  summary.value.circulation.unconfirmedCount = Math.max(
    0,
    summary.value.circulation.unconfirmedCount - 1,
  )
  if (circulationModal.value.item) {
    const removedId = circulationModal.value.item.id
    summary.value.circulation.items = summary.value.circulation.items.filter(
      (i) => i.id !== removedId,
    )
  }
}

function decrementSurvey() {
  if (!summary.value) return
  summary.value.totalActionCount = Math.max(0, summary.value.totalActionCount - 1)
  summary.value.survey.unansweredCount = Math.max(0, summary.value.survey.unansweredCount - 1)
  if (surveyModal.value.item) {
    const removedId = surveyModal.value.item.id
    summary.value.survey.items = summary.value.survey.items.filter((i) => i.id !== removedId)
  }
}

function decrementAttendance() {
  if (!summary.value) return
  summary.value.totalActionCount = Math.max(0, summary.value.totalActionCount - 1)
  summary.value.attendance.unansweredCount = Math.max(
    0,
    summary.value.attendance.unansweredCount - 1,
  )
  if (attendanceModal.value.item) {
    const removedId = attendanceModal.value.item.scheduleId
    summary.value.attendance.items = summary.value.attendance.items.filter(
      (i) => i.scheduleId !== removedId,
    )
  }
}
</script>

<template>
  <div ref="rootEl">
    <SectionCard :title="$t('swipeWidgets.actionRequired.title')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="errorKey" severity="error" :closable="false">
        {{ $t(errorKey) }}
      </Message>

      <DashboardEmptyState
        v-else-if="isEmpty"
        icon="pi pi-check-circle"
        :message="$t('swipeWidgets.actionRequired.empty')"
      />

      <div v-else-if="summary" class="flex flex-col gap-3">
        <!-- 回覧板 -->
        <div>
          <button
            type="button"
            class="flex w-full items-center justify-between text-left text-sm font-medium hover:text-primary"
            @click="navigateTo(circulationPath)"
          >
            <span><i class="pi pi-clipboard mr-2" />{{ $t('swipeWidgets.actionRequired.circulation') }}</span>
            <span class="text-surface-500">
              {{ $t('swipeWidgets.actionRequired.unconfirmed', { count: summary.circulation.unconfirmedCount }) }}
              <i class="pi pi-chevron-right ml-1 text-xs" />
            </span>
          </button>
          <ul class="mt-1 ml-6 list-none text-xs text-surface-500">
            <li v-for="item in summary.circulation.items" :key="item.id">
              <button
                type="button"
                class="w-full truncate text-left hover:text-primary"
                :data-testid="`action-required-circulation-${item.id}`"
                @click="circulationModal = { visible: true, item }"
              >
                {{ item.title }}
              </button>
            </li>
          </ul>
        </div>

        <!-- アンケート -->
        <div>
          <button
            type="button"
            class="flex w-full items-center justify-between text-left text-sm font-medium hover:text-primary"
            @click="navigateTo(surveyListPath)"
          >
            <span><i class="pi pi-file-edit mr-2" />{{ $t('swipeWidgets.actionRequired.survey') }}</span>
            <span class="text-surface-500">
              {{ $t('swipeWidgets.actionRequired.unanswered', { count: summary.survey.unansweredCount }) }}
              <i class="pi pi-chevron-right ml-1 text-xs" />
            </span>
          </button>
          <ul class="mt-1 ml-6 list-none text-xs text-surface-500">
            <li v-for="item in summary.survey.items" :key="item.id">
              <button
                type="button"
                class="w-full truncate text-left hover:text-primary"
                :data-testid="`action-required-survey-${item.id}`"
                @click="surveyModal = { visible: true, item }"
              >
                {{ item.title }}
              </button>
            </li>
          </ul>
        </div>

        <!-- 出席確認 -->
        <div>
          <button
            type="button"
            class="flex w-full items-center justify-between text-left text-sm font-medium hover:text-primary"
            @click="navigateTo(schedulePath)"
          >
            <span><i class="pi pi-check-square mr-2" />{{ $t('swipeWidgets.actionRequired.attendance') }}</span>
            <span class="text-surface-500">
              {{ $t('swipeWidgets.actionRequired.unanswered', { count: summary.attendance.unansweredCount }) }}
              <i class="pi pi-chevron-right ml-1 text-xs" />
            </span>
          </button>
          <ul class="mt-1 ml-6 list-none text-xs text-surface-500">
            <li v-for="item in summary.attendance.items" :key="item.scheduleId">
              <button
                type="button"
                class="w-full truncate text-left hover:text-primary"
                :data-testid="`action-required-attendance-${item.scheduleId}`"
                @click="attendanceModal = { visible: true, item }"
              >
                {{ item.eventTitle }}
              </button>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>
  </div>

  <!-- 回覧板確認モーダル -->
  <CirculationConfirmModal
    v-if="circulationModal.item"
    :visible="circulationModal.visible"
    :item="circulationModal.item"
    :scope-type="scopeType"
    :scope-id="scopeId"
    @update:visible="circulationModal.visible = $event"
    @confirmed="decrementCirculation"
  />

  <!-- アンケート回答モーダル -->
  <SurveyAnswerModal
    v-if="surveyModal.item"
    :visible="surveyModal.visible"
    :item="surveyModal.item"
    :scope-type="scopeType"
    :scope-id="scopeId"
    @update:visible="surveyModal.visible = $event"
    @submitted="decrementSurvey"
  />

  <!-- 出席確認モーダル -->
  <AttendanceQuickModal
    v-if="attendanceModal.item"
    :visible="attendanceModal.visible"
    :item="attendanceModal.item"
    :scope-type="scopeType"
    :scope-id="scopeId"
    @update:visible="attendanceModal.visible = $event"
    @submitted="decrementAttendance"
  />
</template>
