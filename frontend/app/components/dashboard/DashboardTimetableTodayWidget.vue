<script setup lang="ts">
/**
 * F03.15 Phase 3 ダッシュボード「今日の時間割」ウィジェット。
 *
 * 個人ダッシュボードに配置し、所属チーム時間割と個人時間割を時刻順でマージ表示する。
 */
import type { DashboardTimetableToday } from '~/types/timetable-note'

const { t } = useI18n()
const api = useTimetableSlotNoteApi()

const data = ref<DashboardTimetableToday | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    data.value = await api.dashboardToday()
  }
  catch {
    data.value = null
  }
  finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('personalTimetable.dashboard.title')"
    icon="pi pi-calendar"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <p v-if="!data || data.items.length === 0" class="text-sm text-gray-500">
      {{ t('personalTimetable.dashboard.empty') }}
    </p>
    <ul v-else class="space-y-2">
      <li
        v-for="(item, idx) in data.items"
        :key="`${item.source_kind}-${item.slot_id}-${idx}`"
        class="flex items-center text-sm border-l-4 pl-2 py-1"
        :style="item.color ? `border-color:${item.color}` : 'border-color:#cbd5e0'"
      >
        <span class="mr-2 text-gray-500 w-16">
          {{ item.start_time?.slice(0, 5) ?? '--:--' }}
        </span>
        <span class="font-medium mr-1">{{ item.subject_name }}</span>
        <span v-if="item.room_name" class="text-gray-500 mr-1">@ {{ item.room_name }}</span>
        <span class="ml-auto text-xs px-1.5 py-0.5 rounded" :class="item.source_kind === 'PERSONAL' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'">
          {{ item.source_kind === 'PERSONAL' ? t('personalTimetable.dashboard.tag_personal') : t('personalTimetable.dashboard.tag_team') }}
        </span>
        <span v-if="item.has_attachments" class="ml-1 text-xs">📎</span>
      </li>
    </ul>
  </DashboardWidgetCard>
</template>
