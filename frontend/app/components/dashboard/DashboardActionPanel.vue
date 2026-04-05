<script setup lang="ts">
import type { SurveyResponse } from '~/types/survey'
import type { MatchRequestResponse } from '~/types/matching'

interface ScheduleSummary {
  id: number
  title: string
  startAt: string
  responseDeadline: string | null
  myAttendance: string | null
}

interface ActionItem {
  key: string
  type: 'survey' | 'attendance' | 'todo' | 'matching'
  title: string
  scopeName: string
  deadline: string | null
  linkTo: string
}

const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const { getPersonalTodos } = useDashboardApi()
const { getSurveys } = useSurveyApi()
const { listSchedules } = useScheduleApi()
const { getTeamRequests } = useMatchingApi()

const items = ref<ActionItem[]>([])
const loading = ref(false)
const expanded = ref(false)

const SHOW_LIMIT = 5

const displayItems = computed(() =>
  expanded.value ? items.value : items.value.slice(0, SHOW_LIMIT),
)

const typeConfig = {
  survey: {
    label: 'アンケート',
    icon: 'pi pi-chart-bar',
    class: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  },
  attendance: {
    label: '出席確認',
    icon: 'pi pi-calendar-check',
    class: 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300',
  },
  todo: {
    label: 'TODO期限切れ',
    icon: 'pi pi-exclamation-triangle',
    class: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
  },
  matching: {
    label: 'マッチング提案',
    icon: 'pi pi-handshake',
    class: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300',
  },
}

function formatDeadline(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = date.getTime() - now.getTime()
  const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24))
  if (diffDays < 0) return '期限切れ'
  if (diffDays === 0) return '今日まで'
  if (diffDays === 1) return '明日まで'
  return `${diffDays}日後`
}

function deadlineUrgency(dateStr: string | null): string {
  if (!dateStr) return ''
  const diffDays = Math.ceil((new Date(dateStr).getTime() - Date.now()) / 86400000)
  if (diffDays <= 1) return 'text-red-600 font-semibold'
  if (diffDays <= 3) return 'text-orange-500 font-medium'
  return 'text-surface-400'
}

async function load() {
  loading.value = true
  const result: ActionItem[] = []

  try {
    const now = new Date()
    const fromStr = now.toISOString().slice(0, 10)
    const toStr = new Date(now.getTime() + 30 * 86400000).toISOString().slice(0, 10)

    const [todoRes, ...scopeResults] = await Promise.allSettled([
      // 1. TODO 期限切れ
      getPersonalTodos(),

      // 2. アンケート (チーム)
      ...teamStore.myTeams.slice(0, 5).map((team) =>
        getSurveys('TEAM', team.id, { status: 'PUBLISHED', size: 10 }).then((res) => ({
          type: 'survey' as const,
          scopeId: team.id,
          scopeName: team.nickname1 || team.name,
          scopeKind: 'team' as const,
          data: res.data,
        })),
      ),

      // 3. アンケート (組織)
      ...orgStore.myOrganizations.slice(0, 5).map((org) =>
        getSurveys('ORGANIZATION', org.id, { status: 'PUBLISHED', size: 10 }).then((res) => ({
          type: 'survey' as const,
          scopeId: org.id,
          scopeName: org.nickname1 || org.name,
          scopeKind: 'org' as const,
          data: res.data,
        })),
      ),

      // 4. スケジュール出席確認 (チーム)
      ...teamStore.myTeams.slice(0, 5).map((team) =>
        listSchedules('team', team.id, { from: fromStr, to: toStr, size: 10 }).then((res) => ({
          type: 'attendance' as const,
          scopeId: team.id,
          scopeName: team.nickname1 || team.name,
          scopeKind: 'team' as const,
          data: res.data as ScheduleSummary[],
        })),
      ),

      // 5. マッチング提案 (ADMINのチームのみ)
      ...teamStore.myTeams
        .filter((t) => t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN')
        .slice(0, 5)
        .map((team) =>
          getTeamRequests(team.id).then((res) => ({
            type: 'matching' as const,
            scopeId: team.id,
            scopeName: team.nickname1 || team.name,
            scopeKind: 'team' as const,
            data: res.data,
          })),
        ),
    ])

    // TODO 期限切れ
    if (todoRes.status === 'fulfilled' && todoRes.value.data.overdueCount > 0) {
      result.push({
        key: 'todo-overdue',
        type: 'todo',
        title: `${todoRes.value.data.overdueCount}件のTODOが期限切れです`,
        scopeName: '個人',
        deadline: null,
        linkTo: '/todos',
      })
    }

    // アンケート・出席確認・マッチング
    for (const settled of scopeResults) {
      if (settled.status !== 'fulfilled') continue
      const r = settled.value

      if (r.type === 'survey') {
        const surveys = r.data as SurveyResponse[]
        for (const s of surveys.filter((s) => !s.hasResponded)) {
          result.push({
            key: `survey-${r.scopeKind}-${r.scopeId}-${s.id}`,
            type: 'survey',
            title: s.title,
            scopeName: r.scopeName,
            deadline: s.deadline,
            linkTo: `/${r.scopeKind === 'team' ? 'teams' : 'organizations'}/${r.scopeId}/surveys`,
          })
        }
      }

      if (r.type === 'attendance') {
        const schedules = r.data as ScheduleSummary[]
        for (const s of schedules.filter(
          (s) => s.myAttendance === null && s.responseDeadline !== null,
        )) {
          result.push({
            key: `attendance-${r.scopeId}-${s.id}`,
            type: 'attendance',
            title: s.title,
            scopeName: r.scopeName,
            deadline: s.responseDeadline,
            linkTo: `/teams/${r.scopeId}/schedule`,
          })
        }
      }

      if (r.type === 'matching') {
        const requests = r.data as MatchRequestResponse[]
        for (const req of requests.filter(
          (req) => req.status === 'OPEN' && req.proposal_count > 0,
        )) {
          result.push({
            key: `matching-${r.scopeId}-${req.id}`,
            type: 'matching',
            title: `${req.title}（提案 ${req.proposal_count}件）`,
            scopeName: r.scopeName,
            deadline: req.expires_at,
            linkTo: `/teams/${r.scopeId}/matching`,
          })
        }
      }
    }

    // 並び順: todo → deadline昇順 → matching
    result.sort((a, b) => {
      if (a.type === 'todo') return -1
      if (b.type === 'todo') return 1
      if (a.type === 'matching' && b.type !== 'matching') return 1
      if (b.type === 'matching' && a.type !== 'matching') return -1
      if (!a.deadline && !b.deadline) return 0
      if (!a.deadline) return 1
      if (!b.deadline) return -1
      return new Date(a.deadline).getTime() - new Date(b.deadline).getTime()
    })

    items.value = result
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div
    v-if="loading || items.length > 0"
    class="overflow-hidden rounded-xl border border-orange-200 bg-orange-50 dark:border-orange-800/60 dark:bg-orange-900/10"
  >
    <!-- ヘッダー -->
    <div
      class="flex items-center justify-between border-b border-orange-200 px-5 py-3.5 dark:border-orange-800/60"
    >
      <div class="flex items-center gap-2">
        <i class="pi pi-bolt text-orange-500" />
        <span class="font-semibold text-orange-700 dark:text-orange-400">対応が必要なアイテム</span>
        <span
          v-if="!loading"
          class="rounded-full bg-orange-500 px-2 py-0.5 text-xs font-bold text-white"
        >
          {{ items.length }}
        </span>
      </div>
      <Button
        icon="pi pi-refresh"
        text
        rounded
        size="small"
        :loading="loading"
        class="text-orange-400 hover:text-orange-600"
        @click="load"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-2 p-4">
      <Skeleton v-for="i in 3" :key="i" height="2.5rem" class="opacity-40" />
    </div>

    <!-- アイテム一覧 -->
    <div v-else class="divide-y divide-orange-100 dark:divide-orange-800/40">
      <NuxtLink
        v-for="item in displayItems"
        :key="item.key"
        :to="item.linkTo"
        class="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-orange-100/60 dark:hover:bg-orange-900/20"
      >
        <!-- タイプバッジ -->
        <span
          :class="typeConfig[item.type].class"
          class="flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium"
        >
          <i :class="typeConfig[item.type].icon" class="text-xs" />
          {{ typeConfig[item.type].label }}
        </span>

        <!-- タイトル + スコープ -->
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ item.title }}
          </p>
          <p class="text-xs text-surface-400">{{ item.scopeName }}</p>
        </div>

        <!-- 期限 -->
        <span v-if="item.deadline" class="shrink-0 text-xs" :class="deadlineUrgency(item.deadline)">
          {{ formatDeadline(item.deadline) }}
        </span>

        <i class="pi pi-chevron-right shrink-0 text-xs text-orange-300" />
      </NuxtLink>

      <!-- もっと見る / 折りたたむ -->
      <button
        v-if="items.length > SHOW_LIMIT"
        class="w-full py-2.5 text-center text-sm text-orange-500 transition-colors hover:bg-orange-100/60 hover:text-orange-700 dark:hover:bg-orange-900/20"
        @click="expanded = !expanded"
      >
        {{ expanded ? `折りたたむ ∧` : `残り ${items.length - SHOW_LIMIT} 件を表示 ∨` }}
      </button>
    </div>
  </div>
</template>
