<script setup lang="ts">
const { getUpcomingEvents } = useDashboardApi()
const { captureQuiet } = useErrorReport()
const { formatDate, formatDateTime } = useDatetime()
const { t } = useI18n()

interface UpcomingEvent {
  id: number
  /** 司令塔第二弾（ADHD-UX戦役第四陣）: 種別（イベント/本人シフト/本人予約）。 */
  kind?: 'EVENT' | 'SHIFT' | 'RESERVATION'
  title: string
  start_at: string
  end_at: string
  location: string | null
  all_day: boolean
  scope_type: string | null
  scope_name: string | null
  scope_icon_url: string | null
}

/** kind 別のアイコン（シフト=時計・予約=施設・イベント=カレンダー。未知値はカレンダーへ縮退）。 */
function kindIcon(kind: UpcomingEvent['kind']): string {
  if (kind === 'SHIFT') return 'pi pi-clock'
  if (kind === 'RESERVATION') return 'pi pi-building'
  return 'pi pi-calendar'
}

/** kind 別のラベル（未知値・未指定は従来どおりイベント扱い）。 */
function kindLabel(kind: UpcomingEvent['kind']): string {
  if (kind === 'SHIFT') return t('dashboard.upcoming_events.kind_shift')
  if (kind === 'RESERVATION') return t('dashboard.upcoming_events.kind_reservation')
  return t('dashboard.upcoming_events.kind_event')
}

const events = ref<UpcomingEvent[]>([])
const loading = ref(false)
const viewMode = ref<'today' | 'week'>('week')

/** DOM 暴走防止の描画上限（3件ではなく20件・溢れは枠内スクロールで見える）。 */
const DISPLAY_LIMIT = 20
const visibleEvents = computed(() => events.value.slice(0, DISPLAY_LIMIT))

async function load() {
  loading.value = true
  try {
    const days = viewMode.value === 'today' ? 1 : 7
    const res = await getUpcomingEvents(days)
    events.value = res.data
  } catch (error) {
    captureQuiet(error, { context: 'WidgetUpcomingEvents: 直近イベント取得' })
    events.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(dateStr: string): string {
  return formatDateTime(dateStr)
}

watch(viewMode, () => load())
onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="viewMode === 'today' ? t('dashboard.upcoming_events.today_title') : t('dashboard.upcoming_events.week_title')"
    icon="pi pi-calendar"
    to="/calendar"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <!-- 今日 / 今週 トグル -->
    <div class="mb-3 flex gap-1">
      <Button
        :label="t('dashboard.upcoming_events.toggle_today')"
        size="small"
        :outlined="viewMode !== 'today'"
        :text="viewMode !== 'today'"
        @click="viewMode = 'today'"
      />
      <Button
        :label="t('dashboard.upcoming_events.toggle_week')"
        size="small"
        :outlined="viewMode !== 'week'"
        :text="viewMode !== 'week'"
        @click="viewMode = 'week'"
      />
    </div>

    <div v-if="events.length > 0" class="space-y-3">
      <div
        v-for="event in visibleEvents"
        :key="event.id"
        class="flex items-center gap-3 rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
      >
        <div class="flex-1">
          <div class="flex items-center gap-1.5">
            <i
              :class="kindIcon(event.kind)"
              class="text-xs text-surface-400"
              :aria-label="kindLabel(event.kind)"
              :title="kindLabel(event.kind)"
            />
            <p class="text-sm font-medium">{{ event.title }}</p>
          </div>
          <p class="text-xs text-surface-500">
            <i class="pi pi-clock mr-1" />{{
              event.all_day
                ? formatDate(event.start_at)
                : formatTime(event.start_at)
            }}
          </p>
          <p v-if="event.location" class="text-xs text-surface-400">
            <i class="pi pi-map-marker mr-1" />{{ event.location }}
          </p>
          <div
            v-if="event.scope_type && event.scope_type !== 'PERSONAL' && event.scope_name"
            class="flex items-center gap-1.5 mt-1"
          >
            <div
              class="w-5 h-5 rounded-full overflow-hidden flex items-center justify-center bg-surface-200 text-surface-600 text-xs font-bold flex-shrink-0 dark:bg-surface-600 dark:text-surface-200"
            >
              <img
                v-if="event.scope_icon_url"
                :src="event.scope_icon_url"
                class="w-full h-full object-cover"
                alt=""
              >
              <span v-else>{{ event.scope_name.charAt(0) }}</span>
            </div>
            <span class="text-xs text-surface-500">{{ event.scope_name }}</span>
          </div>
        </div>
        <Tag v-if="event.all_day" value="終日" severity="secondary" rounded />
      </div>
      <div class="flex justify-end pt-1">
        <NuxtLink to="/calendar" class="text-sm text-primary hover:underline">
          {{ t('button.view_all') }}
        </NuxtLink>
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-calendar"
      :message="viewMode === 'today' ? t('dashboard.upcoming_events.empty_today') : t('dashboard.upcoming_events.empty_week')"
    />
  </DashboardWidgetCard>
</template>
