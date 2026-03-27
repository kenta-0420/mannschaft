<script setup lang="ts">
const { getUpcomingEvents } = useDashboardApi()

interface Event {
  id: number
  title: string
  startAt: string
  endAt: string
  scopeType: string
  scopeName: string
  attendanceStatus: string | null
}

const events = ref<Event[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getUpcomingEvents(7)
    events.value = res.data
  }
  catch { events.value = [] }
  finally { loading.value = false }
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' }) + ' ' +
    d.toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' })
}

const attendanceColor: Record<string, string> = {
  ATTEND: 'success',
  ABSENT: 'danger',
  UNDECIDED: 'warn',
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard title="今週の予定" icon="pi pi-calendar" :loading="loading" refreshable @refresh="load">
    <div v-if="events.length > 0" class="space-y-3">
      <div
        v-for="event in events"
        :key="event.id"
        class="flex items-center gap-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-700/50"
      >
        <div class="flex-1">
          <p class="text-sm font-medium">{{ event.title }}</p>
          <p class="text-xs text-surface-500">
            <i class="pi pi-clock mr-1" />{{ formatTime(event.startAt) }}
          </p>
          <p class="text-xs text-surface-400">{{ event.scopeName }}</p>
        </div>
        <Tag
          v-if="event.attendanceStatus"
          :value="event.attendanceStatus === 'ATTEND' ? '出席' : event.attendanceStatus === 'ABSENT' ? '欠席' : '未定'"
          :severity="attendanceColor[event.attendanceStatus] ?? 'secondary'"
          rounded
        />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-calendar" message="今週の予定はありません" />
  </DashboardWidgetCard>
</template>
