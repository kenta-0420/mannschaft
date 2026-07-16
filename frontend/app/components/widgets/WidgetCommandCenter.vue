<script setup lang="ts">
import dayjs from 'dayjs'
import {
  mergeCommandCenterData,
  computeCommandCenterCounts,
  toCirculationActionItem,
  toSurveyActionItem,
  toAttendanceActionItem,
  shouldShowAdminSection,
  type CommandCenterItem,
} from '~/composables/useCommandCenter'
import type { CirculationActionItem, SurveyActionItem, AttendanceActionItem, ScopeTabType } from '~/types/dashboard-scope'
import type { PersonalAdminActionItem } from '~/composables/usePersonalAdminActionRequired'

/**
 * ダッシュボード司令塔ウィジェット「今やること」＋「承認待ち」。
 *
 * ADHD-UX戦役 第四陣: 「数字＋文脈＋次の行動」ワンセットの司令塔を最上段に固定表示する。
 * DashboardPersonalPanel.vue に WidgetFamilyHub と同様の v-if 固定パネルとして挿入する
 * （並び替え対象外・KEYS 非登録・設定ダイアログに出ない・AC-1）。
 *
 * データソース:
 * - usePersonalActionRequired: 全チーム/組織横断の回覧板・アンケート・出席確認（既存 API）
 * - useDashboardApi().getDashboardTodoSummary: 本人の期限切れTODO（既存 API・BE新設ゼロ）
 * - usePersonalAdminActionRequired: ADMIN/DEPUTY_ADMIN として管理する全スコープ横断の承認待ち
 *   （司令塔第二弾・新設 API）。管理スコープを持たない/承認待ち0件のユーザーには非表示（AC-B1-3）。
 * いずれかが失敗しても他は表示する縮退設計（AC-3 / AC-B1-4）。
 *
 * 設計書: ADHD-UX戦役 第四陣「ダッシュボード司令塔化 第一弾・第二弾」
 */

const { fetchActionRequired } = usePersonalActionRequired()
const { getDashboardTodoSummary, toggleTodoComplete } = useDashboardApi()
const { fetchAdminActionRequired } = usePersonalAdminActionRequired()
const { userTimezone, formatDate } = useDatetime()
const { captureQuiet } = useErrorReport()
const notification = useNotification()
const { t } = useI18n()

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

const loading = ref(false)
const loaded = ref(false)
const items = ref<CommandCenterItem[]>([])
const actionRequiredFailed = ref(false)
const todoFailed = ref(false)

// === 承認待ち横断集約（司令塔第二弾） ===
const adminItems = ref<PersonalAdminActionItem[]>([])
const adminTotalPending = ref(0)
const adminActionFailed = ref(false)
/** 承認待ちセクションを表示するか。管理スコープなし/承認待ち0件は非表示（AC-B1-3）。取得失敗時は症状を隠さず表示する。 */
const showAdminSection = computed(() =>
  loaded.value && shouldShowAdminSection(adminTotalPending.value, adminActionFailed.value))

const counts = computed(() => computeCommandCenterCounts(items.value))
const isEmpty = computed(() => loaded.value && items.value.length === 0)
const hasFailure = computed(() => actionRequiredFailed.value || todoFailed.value)

async function load() {
  if (loaded.value || loading.value) return
  loading.value = true
  try {
    const [actionSettled, todoSettled, adminSettled] = await Promise.allSettled([
      fetchActionRequired(),
      getDashboardTodoSummary().then((res) => res.data),
      fetchAdminActionRequired(),
    ])

    if (actionSettled.status === 'rejected') {
      captureQuiet(actionSettled.reason, { context: 'WidgetCommandCenter: action-required取得' })
    }
    if (todoSettled.status === 'rejected') {
      captureQuiet(todoSettled.reason, { context: 'WidgetCommandCenter: dashboard/todos取得' })
    }
    if (adminSettled.status === 'rejected') {
      captureQuiet(adminSettled.reason, { context: 'WidgetCommandCenter: admin-action-required取得' })
      adminActionFailed.value = true
    } else {
      adminItems.value = adminSettled.value.items
      adminTotalPending.value = adminSettled.value.totalPending
    }

    const result = mergeCommandCenterData(actionSettled, todoSettled, dayjs(), userTimezone.value)
    items.value = result.items
    actionRequiredFailed.value = result.actionRequiredFailed
    todoFailed.value = result.todoFailed
  } finally {
    loaded.value = true
    loading.value = false
  }
}

function onAdminItemClick(item: PersonalAdminActionItem) {
  navigateTo(item.detailRoute)
}

function observeViewport() {
  if (!import.meta.client || !rootEl.value) return
  observer?.disconnect()
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        load()
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

function removeItem(key: string) {
  items.value = items.value.filter((i) => i.key !== key)
}

// === モーダル状態 ===
const circulationModal = ref<{
  visible: boolean
  item: CirculationActionItem | null
  scopeType: ScopeTabType
  scopeId: string
  key: string
}>({ visible: false, item: null, scopeType: 'TEAM', scopeId: '', key: '' })

const surveyModal = ref<{
  visible: boolean
  item: SurveyActionItem | null
  scopeType: ScopeTabType
  scopeId: string
  key: string
}>({ visible: false, item: null, scopeType: 'TEAM', scopeId: '', key: '' })

const attendanceModal = ref<{
  visible: boolean
  item: AttendanceActionItem | null
  scopeType: ScopeTabType
  scopeId: string
  key: string
}>({ visible: false, item: null, scopeType: 'TEAM', scopeId: '', key: '' })

function onItemClick(item: CommandCenterItem) {
  if (item.kind === 'TODO') {
    if (item.todoId != null) navigateTo(`/todos/${item.todoId}`)
    return
  }
  const action = item.actionItem
  if (!action) return
  const scopeId = String(action.scopeId)

  if (item.kind === 'CIRCULATION') {
    circulationModal.value = {
      visible: true,
      item: toCirculationActionItem(action),
      scopeType: action.scopeType,
      scopeId,
      key: item.key,
    }
  } else if (item.kind === 'SURVEY') {
    surveyModal.value = {
      visible: true,
      item: toSurveyActionItem(action),
      scopeType: action.scopeType,
      scopeId,
      key: item.key,
    }
  } else if (item.kind === 'ATTENDANCE') {
    attendanceModal.value = {
      visible: true,
      item: toAttendanceActionItem(action),
      scopeType: action.scopeType,
      scopeId,
      key: item.key,
    }
  }
}

function onCirculationConfirmed() {
  removeItem(circulationModal.value.key)
}

function onSurveySubmitted() {
  removeItem(surveyModal.value.key)
}

function onAttendanceSubmitted() {
  removeItem(attendanceModal.value.key)
}

async function onCompleteTodo(item: CommandCenterItem) {
  if (item.todoId == null) return
  try {
    await toggleTodoComplete(item.todoId, true)
    removeItem(item.key)
    notification.success(t('dashboard.commandCenter.todoCompleteSuccess'))
  } catch (error) {
    captureQuiet(error, { context: 'WidgetCommandCenter: TODO完了' })
    notification.error(t('dashboard.commandCenter.todoCompleteError'))
  }
}

const KIND_ICON: Record<CommandCenterItem['kind'], string> = {
  CIRCULATION: 'pi pi-clipboard',
  SURVEY: 'pi pi-file-edit',
  ATTENDANCE: 'pi pi-check-square',
  TODO: 'pi pi-list-check',
}

function deadlineText(item: CommandCenterItem): string {
  if (item.deadlineLabel === 'today') return t('dashboard.commandCenter.deadlineToday')
  if (item.deadlineLabel === 'overdue') return t('dashboard.commandCenter.deadlineOverdue')
  if (item.deadlineLabel === 'upcoming') return formatDate(item.deadline)
  return ''
}

function deadlineTextClass(item: CommandCenterItem): string {
  if (item.deadlineLabel === 'overdue') return 'font-semibold text-red-500'
  if (item.deadlineLabel === 'today') return 'font-semibold text-amber-500'
  return 'text-surface-400'
}
</script>

<template>
  <div ref="rootEl" class="mb-4">
    <SectionCard>
      <!-- 初回ロード中スケルトン -->
      <div v-if="!loaded" class="space-y-3">
        <Skeleton height="2rem" width="40%" />
        <Skeleton height="1rem" width="70%" />
        <Skeleton height="1rem" width="55%" />
      </div>

      <!-- 0件（縮退表示・AC-2） -->
      <DashboardEmptyState
        v-else-if="isEmpty"
        :icon="hasFailure ? 'pi pi-exclamation-triangle' : 'pi pi-check-circle'"
        :message="hasFailure ? $t('dashboard.commandCenter.partialLoadError') : $t('dashboard.commandCenter.emptyTitle')"
        :sub-message="hasFailure ? undefined : $t('dashboard.commandCenter.emptyMessage')"
      />

      <template v-else>
        <!-- ヘッダー: 大数字 ＋ 内訳チップ -->
        <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
          <div>
            <p class="text-xs font-semibold uppercase tracking-wide text-surface-400">
              {{ $t('dashboard.commandCenter.title') }}
            </p>
            <p
              class="text-3xl font-bold text-surface-800 dark:text-surface-100"
              data-testid="command-center-total"
            >
              {{ $t('dashboard.commandCenter.unresolvedCount', { count: counts.total }) }}
            </p>
          </div>
          <div class="flex flex-wrap gap-2">
            <span
              v-if="counts.circulation > 0"
              class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 text-xs font-semibold text-primary"
            >
              <i class="pi pi-clipboard text-[11px]" />{{ $t('dashboard.commandCenter.chipCirculation') }} {{ counts.circulation }}
            </span>
            <span
              v-if="counts.survey > 0"
              class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 text-xs font-semibold text-primary"
            >
              <i class="pi pi-file-edit text-[11px]" />{{ $t('dashboard.commandCenter.chipSurvey') }} {{ counts.survey }}
            </span>
            <span
              v-if="counts.attendance > 0"
              class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 text-xs font-semibold text-primary"
            >
              <i class="pi pi-check-square text-[11px]" />{{ $t('dashboard.commandCenter.chipAttendance') }} {{ counts.attendance }}
            </span>
            <span
              v-if="counts.overdueTodo > 0"
              class="inline-flex items-center gap-1 rounded-full bg-red-100 px-2.5 py-1 text-xs font-semibold text-red-700 dark:bg-red-900/30 dark:text-red-400"
            >
              <i class="pi pi-list-check text-[11px]" />{{ $t('dashboard.commandCenter.chipOverdueTodo') }} {{ counts.overdueTodo }}
            </span>
          </div>
        </div>

        <!-- 片系失敗の縮退バナー（AC-3） -->
        <Message v-if="hasFailure" severity="warn" :closable="false" class="mb-3 text-xs">
          {{ $t('dashboard.commandCenter.partialLoadError') }}
        </Message>

        <!-- リスト -->
        <TransitionGroup
          tag="div"
          class="divide-y divide-surface-200 dark:divide-surface-700"
          move-class="transition-all duration-300"
        >
          <div
            v-for="item in items"
            :key="item.key"
            class="flex items-center gap-3 py-2.5"
            :data-testid="`command-center-item-${item.key}`"
          >
            <i :class="KIND_ICON[item.kind]" class="shrink-0 text-lg text-surface-400" />
            <button
              type="button"
              class="min-w-0 flex-1 text-left hover:text-primary"
              @click="onItemClick(item)"
            >
              <p class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
                {{ item.title }}
              </p>
              <p class="text-xs">
                <span v-if="item.scopeName" class="text-surface-400">{{ item.scopeName }}<span v-if="deadlineText(item)"> ・ </span></span>
                <span :class="deadlineTextClass(item)">{{ deadlineText(item) }}</span>
              </p>
            </button>
            <Checkbox
              v-if="item.kind === 'TODO'"
              :model-value="false"
              binary
              :aria-label="$t('dashboard.commandCenter.todoCompleteAria')"
              @click.stop
              @update:model-value="onCompleteTodo(item)"
            />
            <i v-else class="pi pi-chevron-right shrink-0 text-xs text-surface-400" />
          </div>
        </TransitionGroup>
      </template>
    </SectionCard>

    <!-- 承認待ち横断集約（司令塔第二弾）: 管理スコープ保持ユーザーのみ・0件時非表示（AC-B1-3） -->
    <SectionCard v-if="showAdminSection" class="mt-4" data-testid="command-center-admin-section">
      <div class="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p class="text-xs font-semibold uppercase tracking-wide text-surface-400">
            {{ $t('dashboard.commandCenter.adminSectionTitle') }}
          </p>
          <p
            v-if="adminTotalPending > 0"
            class="text-2xl font-bold text-surface-800 dark:text-surface-100"
            data-testid="command-center-admin-total"
          >
            {{ $t('dashboard.commandCenter.adminUnresolvedCount', { count: adminTotalPending }) }}
          </p>
        </div>
      </div>

      <!-- 取得失敗（症状を隠さず表示・AC-B1-4） -->
      <Message v-if="adminActionFailed" severity="warn" :closable="false" class="text-xs" data-testid="command-center-admin-error">
        {{ $t('dashboard.commandCenter.adminLoadError') }}
      </Message>

      <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
        <li
          v-for="item in adminItems"
          :key="`${item.domain}-${item.scopeType}-${item.scopeId}-${item.itemId}`"
        >
          <button
            type="button"
            class="flex w-full items-center gap-3 py-2.5 text-left hover:text-primary"
            :data-testid="`command-center-admin-item-${item.domain}-${item.itemId}`"
            @click="onAdminItemClick(item)"
          >
            <i class="pi pi-verified shrink-0 text-lg text-surface-400" />
            <span class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
                {{ item.title }}
              </p>
              <p class="truncate text-xs text-surface-400">
                {{ item.scopeName }} ・ {{ $t(`adminConsole.lens.approvals.domain.${item.domain}`) }}
                <span v-if="item.requestedBy"> ・ {{ item.requestedBy }}</span>
              </p>
            </span>
            <i class="pi pi-chevron-right shrink-0 text-xs text-surface-400" />
          </button>
        </li>
      </ul>
    </SectionCard>

    <!-- 回覧板確認モーダル -->
    <CirculationConfirmModal
      v-if="circulationModal.item"
      :visible="circulationModal.visible"
      :item="circulationModal.item"
      :scope-type="circulationModal.scopeType"
      :scope-id="circulationModal.scopeId"
      @update:visible="circulationModal.visible = $event"
      @confirmed="onCirculationConfirmed"
    />

    <!-- アンケート回答モーダル -->
    <SurveyAnswerModal
      v-if="surveyModal.item"
      :visible="surveyModal.visible"
      :item="surveyModal.item"
      :scope-type="surveyModal.scopeType"
      :scope-id="surveyModal.scopeId"
      @update:visible="surveyModal.visible = $event"
      @submitted="onSurveySubmitted"
    />

    <!-- 出席確認モーダル -->
    <AttendanceQuickModal
      v-if="attendanceModal.item"
      :visible="attendanceModal.visible"
      :item="attendanceModal.item"
      :scope-type="attendanceModal.scopeType"
      :scope-id="attendanceModal.scopeId"
      @update:visible="attendanceModal.visible = $event"
      @submitted="onAttendanceSubmitted"
    />
  </div>
</template>
