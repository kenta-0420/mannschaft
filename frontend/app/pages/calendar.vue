<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const router = useRouter()
const scheduleApi = useScheduleApi()

const now = new Date()
const currentYear = ref(now.getFullYear())
const currentMonth = ref(now.getMonth() + 1)

interface CalEvent {
  id: number
  title: string
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  scopeType: string
  isPersonal: boolean
}

const events = ref<CalEvent[]>([])
const loading = ref(true)
const showCreateDialog = ref(false)
const selectedDate = ref<string | undefined>(undefined)

async function loadEvents() {
  loading.value = true
  try {
    const from = `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}-01`
    const to = `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}-28`
    const [personal, shared] = await Promise.all([
      scheduleApi.listPersonalSchedules({ from, to }),
      scheduleApi.getCalendarMonth(currentYear.value, currentMonth.value),
    ])
    const personalEvents = ((personal.data ?? []) as CalEvent[]).map((e) => ({
      ...e,
      isPersonal: true,
      scopeType: 'PERSONAL',
      color: ((e as Record<string, unknown>).color as string) ?? '#22c55e',
    }))
    const sharedEvents = (
      ((shared.data as Record<string, unknown>)?.events as CalEvent[]) ?? []
    ).map((e) => ({ ...e, isPersonal: false }))
    events.value = [...personalEvents, ...sharedEvents]
  } catch {
    events.value = []
  } finally {
    loading.value = false
  }
}

function onDateClick(date: string) {
  selectedDate.value = date
  showCreateDialog.value = true
}

function onPrevMonth() {
  if (currentMonth.value === 1) {
    currentMonth.value = 12
    currentYear.value--
  } else {
    currentMonth.value--
  }
  loadEvents()
}

function onNextMonth() {
  if (currentMonth.value === 12) {
    currentMonth.value = 1
    currentYear.value++
  } else {
    currentMonth.value++
  }
  loadEvents()
}

onMounted(loadEvents)
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
        <h1 class="text-2xl font-bold">マイカレンダー</h1>
      </div>
      <Button label="予定を追加" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <div
      class="rounded-xl border-2 border-surface-400 bg-surface-0 p-4 dark:border-surface-500 dark:bg-surface-800"
    >
      <CalendarGrid
        :year="currentYear"
        :month="currentMonth"
        :events="events"
        @date-click="onDateClick"
        @prev-month="onPrevMonth"
        @next-month="onNextMonth"
      />
    </div>

    <div class="mt-4 flex gap-4 text-xs text-surface-500">
      <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-green-500" />個人</span>
      <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-indigo-500" />チーム/組織</span>
    </div>

    <EventForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="0"
      :initial-date="selectedDate"
      :is-personal="true"
      @saved="loadEvents"
    />
  </div>
</template>
