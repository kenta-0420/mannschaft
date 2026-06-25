<script setup lang="ts">
const props = defineProps<{
  event: {
    id: number
    title: string
    description: string | null
    location: string | null
    startAt: string
    endAt: string
    allDay: boolean
    status: string
    categoryName: string | null
    categoryColor: string | null
    createdBy: { displayName: string }
    attendanceRequired: boolean
    myAttendance: string | null
    attendanceStats: { yes: number; no: number; maybe: number; pending: number; total: number } | null
  }
  scopeType: 'team' | 'organization'
  scopeId: string
  canEdit: boolean
  skipDelegations?: boolean
  scopeName?: string | null
  scopeIconUrl?: string | null
}>()

const emit = defineEmits<{
  edit: []
  delete: []
  responded: []
}>()

const { formatDate, formatDateTime: isoFormatDateTime } = useDatetime()
const { t } = useI18n()
const scheduleApi = useScheduleApi()
const notification = useNotification()

// F08.10 入口④: カレンダー予定（TEAM スコープ）から試合記録へ合流する。
// 予定の publicId(scopeId) を数値 orgId/teamId に解決し、既存 match があれば live を開く・
// 無ければ予定情報をプリフィルして作成 → live へ遷移する（二重起票防止）。
const { resolveContext } = useMatchOrgContext()
const { resolveMatchBySchedule, createMatch } = useMatchApi()
// TEAM スコープ予定のみ記録ボタンを出す（teamId 文脈が要るため・organization/personal では非表示）。
// Bug B 修正: scopeId が空の場合（個人予定）はボタンを非表示にする。
const canRecordMatch = computed(() => props.scopeType === 'team' && !!props.scopeId)
const recordingMatch = ref(false)

async function recordMatch(): Promise<void> {
  if (!canRecordMatch.value || recordingMatch.value) return
  recordingMatch.value = true
  try {
    const ctx = await resolveContext(props.scopeId)
    if (!ctx) {
      // Bug C 修正: ctx が null の場合（チームが組織に所属していない等）はユーザーへ通知する。
      // 以前のコメント「composable 内で通知済み」は誤記で、実際には通知されていなかった。
      notification.warn(t('match.entry.no_org_for_record'))
      return
    }

    // 1) この予定に紐づく既存 match があれば live を開く
    const existing = await resolveMatchBySchedule(ctx.orgId, ctx.teamId, props.event.id)
    if (existing?.id) {
      await navigateTo(`/teams/${props.scopeId}/matches/${existing.id}/live`)
      return
    }

    // 2) 無ければ予定情報をプリフィルして作成（kind 既定=PRACTICE・相手名/日時/会場は取れる範囲）
    // BE CreateMatchRequest.kickoffAt は LocalDateTime（タイムゾーンなし）のため、
    // ScheduleResponse.time.startAt の OffsetDateTime 形式（"+09:00" 等）のオフセットを除去する。
    const kickoffAtLocal = props.event.startAt.replace(/[+-]\d{2}:\d{2}$/, '')
    const created = await createMatch(ctx.orgId, ctx.teamId, {
      kind: 'PRACTICE',
      opponentName: props.event.title,
      scheduleId: props.event.id,
      kickoffAt: kickoffAtLocal,
      venue: props.event.location ?? undefined,
    })
    if (created.id) {
      await navigateTo(`/teams/${props.scopeId}/matches/${created.id}/live`)
    }
  } catch {
    // エラーは composable 内で通知済み（症状は隠さない）
  } finally {
    recordingMatch.value = false
  }
}

function formatDateTime(dateStr: string, allDay: boolean): string {
  if (allDay) return formatDate(dateStr)
  return isoFormatDateTime(dateStr)
}

// 機能55: 予約タスク（PENDING の取消対応）
interface ScheduledTaskView {
  id: string
  taskType: string
  scheduledAt: string
  status: string
}
const scheduledTasks = ref<ScheduledTaskView[]>([])
const cancellingTaskId = ref<string | null>(null)

const taskTypeLabel: Record<string, string> = {
  SURVEY: t('schedule.scheduled_task.type_survey'),
  ATTENDANCE: t('schedule.scheduled_task.type_attendance'),
}

const taskStatusConfig: Record<string, { label: string; severity: string }> = {
  PENDING: { label: t('schedule.scheduled_task.status_pending'), severity: 'info' },
  CREATED: { label: t('schedule.scheduled_task.status_created'), severity: 'success' },
  CANCELLED: { label: t('schedule.scheduled_task.status_cancelled'), severity: 'secondary' },
  FAILED: { label: t('schedule.scheduled_task.status_failed'), severity: 'danger' },
}

async function loadScheduledTasks() {
  try {
    const res = await scheduleApi.getSchedule(props.scopeType, props.scopeId, props.event.id)
    const data = (res as { data: Record<string, unknown> }).data ?? {}
    const raw = (data.scheduledTasks as ScheduledTaskView[] | undefined) ?? []
    scheduledTasks.value = raw
  } catch {
    // 補助表示なので失敗時は空扱いで継続
    scheduledTasks.value = []
  }
}

async function cancelTask(taskId: string) {
  cancellingTaskId.value = taskId
  try {
    await scheduleApi.cancelScheduledTask(props.scopeType, props.scopeId, props.event.id, taskId)
    notification.success(t('schedule.scheduled_task.cancel_success'))
    await loadScheduledTasks()
  } catch {
    notification.error(t('schedule.scheduled_task.cancel_failed'))
  } finally {
    cancellingTaskId.value = null
  }
}

const statusConfig: Record<string, { label: string; severity: string }> = {
  DRAFT: { label: '下書き', severity: 'secondary' },
  PUBLISHED: { label: '公開中', severity: 'success' },
  CANCELLED: { label: 'キャンセル', severity: 'danger' },
}

// F03.10 第四陣 Wave2-B 代理出席件数バッジ
const { fetchDelegations } = useEventDelegationApi()
const delegationCount = ref(0)

onMounted(async () => {
  if (props.canEdit && !props.skipDelegations) {
    try {
      const res = await fetchDelegations(props.event.id, 1, 1)
      delegationCount.value = res.total ?? 0
    } catch {
      // サイドパネルの補助情報なので失敗しても 0 件扱いで継続
      delegationCount.value = 0
    }
  }
  // 機能55: 予約タスクは編集権限者かつチーム/組織スコープにのみ表示・取消可能
  // 個人予定（scopeId が空）の場合は GET /teams//schedules/{id} の二重スラッシュを避けるためスキップ
  if (props.canEdit && props.scopeId) {
    await loadScheduledTasks()
  }
})
</script>

<template>
  <div class="space-y-4">
    <!-- ヘッダー -->
    <div class="flex items-start justify-between">
      <div>
        <div class="flex items-center gap-2">
          <h2 class="text-xl font-bold">{{ event.title }}</h2>
          <Tag
            v-if="event.categoryName"
            :value="event.categoryName"
            :style="{ backgroundColor: (event.categoryColor ?? '#6366f1') + '20', color: event.categoryColor ?? '#6366f1' }"
            rounded
          />
        </div>
        <Tag
          :value="statusConfig[event.status]?.label ?? event.status"
          :severity="statusConfig[event.status]?.severity ?? 'secondary'"
          class="mt-1"
          rounded
        />
      </div>
      <div v-if="canEdit" class="flex gap-1">
        <Button icon="pi pi-pencil" text rounded size="small" @click="emit('edit')" />
        <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="emit('delete')" />
      </div>
    </div>

    <!-- 日時・場所 -->
    <div class="space-y-2 text-sm">
      <div class="flex items-center gap-2">
        <i class="pi pi-calendar text-surface-400" />
        <span>{{ formatDateTime(event.startAt, event.allDay) }} 〜 {{ formatDateTime(event.endAt, event.allDay) }}</span>
      </div>
      <div v-if="event.location" class="flex items-center gap-2">
        <i class="pi pi-map-marker text-surface-400" />
        <span>{{ event.location }}</span>
      </div>
      <div v-if="scopeName" class="flex items-center gap-2">
        <div class="w-5 h-5 rounded-full overflow-hidden flex items-center justify-center bg-surface-200 text-surface-600 text-xs font-bold flex-shrink-0 dark:bg-surface-600 dark:text-surface-200">
          <img v-if="scopeIconUrl" :src="scopeIconUrl" class="w-full h-full object-cover" alt="">
          <span v-else>{{ scopeName.charAt(0) }}</span>
        </div>
        <span>{{ scopeName }}</span>
      </div>
      <div v-if="event.createdBy" class="flex items-center gap-2">
        <i class="pi pi-user text-surface-400" />
        <span>作成: {{ event.createdBy.displayName }}</span>
      </div>
    </div>

    <!-- F08.10 入口④: TEAM スコープ予定のみ「この試合を記録」ボタンを出す -->
    <div v-if="canRecordMatch">
      <Button
        :label="$t('match.entry.record_from_schedule')"
        icon="pi pi-play"
        outlined
        size="small"
        class="w-full"
        :loading="recordingMatch"
        @click="recordMatch"
      />
      <p class="mt-1 text-xs text-surface-400">{{ $t('match.entry.record_from_schedule_hint') }}</p>
    </div>

    <!-- 説明 -->
    <div v-if="event.description" class="rounded-lg bg-surface-50 p-3 dark:bg-surface-700/50">
      <p class="whitespace-pre-wrap text-sm">{{ event.description }}</p>
    </div>

    <!-- 出欠パネル -->
    <AttendancePanel
      v-if="event.attendanceRequired"
      :scope-type="scopeType"
      :scope-id="scopeId"
      :schedule-id="event.id"
      :my-attendance="event.myAttendance"
      :stats="event.attendanceStats"
      @responded="emit('responded')"
    />

    <!-- F03.1 (B) 組織出欠のチーム別内訳（組織スコープ + 管理者のみ）。
         認可は BE 側 org-ADMIN 限定 EP。ここでは出し分けの一次フィルタとして
         組織スコープ + canEdit(管理者) + 出欠あり を要求する（403 時はパネル内で明示表示）。 -->
    <AttendanceTeamBreakdownPanel
      v-if="event.attendanceRequired && scopeType === 'organization' && canEdit"
      :org-public-id="scopeId"
      :schedule-id="event.id"
    />

    <!-- 機能55: 予約タスク一覧（管理者 + 1件以上の場合のみ表示） -->
    <div
      v-if="canEdit && scheduledTasks.length > 0"
      class="space-y-2"
    >
      <h3 class="text-sm font-medium">{{ $t('schedule.scheduled_task.label') }}</h3>
      <div
        v-for="task in scheduledTasks"
        :key="task.id"
        class="flex items-center justify-between gap-2 rounded border border-surface-200 p-2 text-sm dark:border-surface-600"
      >
        <div class="flex flex-col gap-0.5">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ taskTypeLabel[task.taskType] ?? task.taskType }}</span>
            <Tag
              :value="taskStatusConfig[task.status]?.label ?? task.status"
              :severity="taskStatusConfig[task.status]?.severity ?? 'secondary'"
              rounded
            />
          </div>
          <span class="text-xs text-surface-500">{{ isoFormatDateTime(task.scheduledAt) }}</span>
        </div>
        <Button
          v-if="task.status === 'PENDING'"
          :label="$t('schedule.scheduled_task.cancel')"
          icon="pi pi-times"
          text
          size="small"
          severity="danger"
          :loading="cancellingTaskId === task.id"
          @click="cancelTask(task.id)"
        />
      </div>
    </div>

    <!-- F03.10 第四陣 Wave2-B 代理出席件数バッジ（管理者 + 1件以上の場合のみ表示） -->
    <div
      v-if="canEdit && delegationCount > 0"
      class="mt-3 rounded border border-yellow-200 bg-yellow-50 p-2 text-sm dark:border-yellow-800 dark:bg-yellow-900/20"
    >
      <span class="font-medium text-yellow-800 dark:text-yellow-200">{{ $t('proxy.delegation.admin.tab') }}: </span>
      <span class="text-yellow-700 dark:text-yellow-300">{{ delegationCount }}{{ $t('proxy.delegation.admin.count_suffix') }}</span>
    </div>
  </div>
</template>
