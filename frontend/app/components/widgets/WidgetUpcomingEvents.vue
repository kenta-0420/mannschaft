<script setup lang="ts">
const { getUpcomingEvents } = useDashboardApi()
const { captureQuiet } = useErrorReport()

interface UpcomingEvent {
  id: number
  title: string
  start_at: string
  end_at: string
  location: string | null
  all_day: boolean
}

const events = ref<UpcomingEvent[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getUpcomingEvents(7)
    events.value = res.data
  } catch (error) {
    captureQuiet(error, { context: 'WidgetUpcomingEvents: 直近イベント取得' })
    events.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  return (
    d.toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' }) +
    ' ' +
    d.toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' })
  )
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="今週の予定"
    icon="pi pi-calendar"
    to="/calendar"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="events.length > 0" class="space-y-3">
      <div
        v-for="event in events"
        :key="event.id"
        class="flex items-center gap-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-700/50"
      >
        <div class="flex-1">
          <p class="text-sm font-medium">{{ event.title }}</p>
          <p class="text-xs text-surface-500">
            <i class="pi pi-clock mr-1" />{{
              event.all_day
                ? new Date(event.start_at).toLocaleDateString('ja-JP', {
                    month: 'short',
                    day: 'numeric',
                  })
                : formatTime(event.start_at)
            }}
          </p>
          <p v-if="event.location" class="text-xs text-surface-400">
            <i class="pi pi-map-marker mr-1" />{{ event.location }}
          </p>
        </div>
        <Tag v-if="event.all_day" value="終日" severity="secondary" rounded />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-calendar" message="今週の予定はありません" />
  </DashboardWidgetCard>
</template>
