<script setup lang="ts">
/**
 * F03.15 Phase 3 ダッシュボード「今日のメモ」ウィジェット。
 *
 * 今日のコマに紐付くメモの冒頭をカード形式で表示。
 */
import type { TimetableSlotUserNote } from '~/types/timetable-note'

const { t } = useI18n()
const api = useTimetableSlotNoteApi()

const notes = ref<TimetableSlotUserNote[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    notes.value = await api.todayNotes()
  }
  catch {
    notes.value = []
  }
  finally {
    loading.value = false
  }
}

function snippet(text: string | null | undefined, max = 80): string {
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('personalTimetable.notes.dashboard_title')"
    icon="pi pi-file-edit"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <p v-if="notes.length === 0" class="text-sm text-gray-500">
      {{ t('personalTimetable.notes.dashboard_empty') }}
    </p>
    <ul v-else class="space-y-2">
      <li
        v-for="note in notes"
        :key="note.id"
        class="text-sm border-l-2 border-gray-300 pl-2 py-1"
      >
        <p v-if="note.preparation" class="text-gray-700">
          <span class="font-medium">{{ t('personalTimetable.notes.field_preparation') }}:</span>
          {{ snippet(note.preparation) }}
        </p>
        <p v-if="note.items_to_bring" class="text-gray-700">
          <span class="font-medium">{{ t('personalTimetable.notes.field_items_to_bring') }}:</span>
          {{ snippet(note.items_to_bring) }}
        </p>
        <p v-if="note.free_memo" class="text-gray-700">
          <span class="font-medium">{{ t('personalTimetable.notes.field_free_memo') }}:</span>
          {{ snippet(note.free_memo) }}
        </p>
      </li>
    </ul>
  </DashboardWidgetCard>
</template>
