<script setup lang="ts">
import dayjs from 'dayjs'

const { getPersonalTodos } = useDashboardApi()
const { captureQuiet } = useErrorReport()
const { userTimezone } = useDatetime()

interface TodoItem {
  id: number
  title: string
  dueDate: string | null
  status: string
  priority: string
}

const todos = ref<TodoItem[]>([])
const now = ref(Date.now())
let intervalId: ReturnType<typeof setInterval>

async function load() {
  try {
    const res = await getPersonalTodos()
    todos.value = (res.data ?? [])
      .filter((t) => t.dueDate && t.status !== 'COMPLETED' && t.status !== 'CANCELLED')
      .sort((a, b) =>
        dayjs.tz(a.dueDate!, userTimezone.value).valueOf()
        - dayjs.tz(b.dueDate!, userTimezone.value).valueOf(),
      )
      .slice(0, 5) as TodoItem[]
  }
  catch (e) {
    captureQuiet(e, { context: 'WidgetTodoCountdown: TODO取得' })
  }
}

function formatCountdown(dueDate: string): { text: string; urgent: boolean; overdue: boolean } {
  // ユーザーのTZで dueDate の midnight を取得することで UTC 解析による時刻ズレを防ぐ
  const dueDateMs = dayjs.tz(dueDate, userTimezone.value).valueOf()
  const ms = dueDateMs - now.value
  if (ms < 0) {
    const overMs = -ms
    const h = Math.floor(overMs / 3600000)
    const m = Math.floor((overMs % 3600000) / 60000)
    return { text: `${h > 0 ? `${h}時間` : ''}${m}分 超過`, urgent: false, overdue: true }
  }
  const DAY = 86400000
  if (ms < DAY) {
    const h = Math.floor(ms / 3600000)
    const m = Math.floor((ms % 3600000) / 60000)
    const s = Math.floor((ms % 60000) / 1000)
    return {
      text: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`,
      urgent: true,
      overdue: false,
    }
  }
  const days = Math.floor(ms / DAY)
  const h = Math.floor((ms % DAY) / 3600000)
  return { text: `${days}日 ${h}時間`, urgent: days <= 3, overdue: false }
}

const priorityColor: Record<string, string> = {
  HIGH: 'bg-red-500',
  MEDIUM: 'bg-yellow-400',
  LOW: 'bg-green-400',
}

onMounted(() => {
  load()
  intervalId = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => clearInterval(intervalId))
</script>

<template>
  <DashboardWidgetCard
    title="TODOカウントダウン"
    icon="pi pi-stopwatch"
    to="/todos"
    refreshable
    @refresh="load"
  >
    <div v-if="todos.length === 0" class="py-2 text-center text-sm text-surface-400">
      期限付きTODOはありません
    </div>

    <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
      <div
        v-for="todo in todos"
        :key="todo.id"
        class="flex items-center gap-3 py-2.5"
      >
        <span
          class="h-2 w-2 shrink-0 rounded-full"
          :class="priorityColor[todo.priority] ?? 'bg-surface-300'"
        />

        <div class="min-w-0 flex-1">
          <NuxtLink
            :to="`/todos/${todo.id}`"
            class="block truncate text-sm font-medium hover:text-primary"
          >
            {{ todo.title }}
          </NuxtLink>
        </div>

        <div
          v-if="todo.dueDate"
          class="shrink-0 font-mono text-sm tabular-nums"
          :class="{
            'animate-pulse font-bold text-red-600': formatCountdown(todo.dueDate).overdue,
            'font-bold text-orange-500': formatCountdown(todo.dueDate).urgent && !formatCountdown(todo.dueDate).overdue,
            'text-surface-500': !formatCountdown(todo.dueDate).urgent && !formatCountdown(todo.dueDate).overdue,
          }"
        >
          {{ formatCountdown(todo.dueDate).text }}
        </div>
      </div>
    </div>
  </DashboardWidgetCard>
</template>
