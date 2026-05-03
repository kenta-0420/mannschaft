<script setup lang="ts">
import type { LocationTimelineResponse, AttendanceLocation } from '~/types/school'

defineProps<{
  timeline: LocationTimelineResponse
}>()

const { t } = useI18n()

// ロケーションに対応するTailwindクラス（アイコン代替の色帯）
function locationColorClass(location: AttendanceLocation): string {
  switch (location) {
    case 'CLASSROOM':
      return 'bg-green-100 text-green-800 border-green-300 dark:bg-green-900 dark:text-green-200 dark:border-green-700'
    case 'SICK_BAY':
      return 'bg-yellow-100 text-yellow-800 border-yellow-300 dark:bg-yellow-900 dark:text-yellow-200 dark:border-yellow-700'
    case 'SEPARATE_ROOM':
      return 'bg-blue-100 text-blue-800 border-blue-300 dark:bg-blue-900 dark:text-blue-200 dark:border-blue-700'
    case 'LIBRARY':
      return 'bg-purple-100 text-purple-800 border-purple-300 dark:bg-purple-900 dark:text-purple-200 dark:border-purple-700'
    case 'ONLINE':
      return 'bg-sky-100 text-sky-800 border-sky-300 dark:bg-sky-900 dark:text-sky-200 dark:border-sky-700'
    case 'HOME_LEARNING':
      return 'bg-orange-100 text-orange-800 border-orange-300 dark:bg-orange-900 dark:text-orange-200 dark:border-orange-700'
    case 'OUT_OF_SCHOOL':
      return 'bg-rose-100 text-rose-800 border-rose-300 dark:bg-rose-900 dark:text-rose-200 dark:border-rose-700'
    case 'NOT_APPLICABLE':
    default:
      return 'bg-surface-100 text-surface-500 border-surface-200 dark:bg-surface-800 dark:text-surface-400 dark:border-surface-700'
  }
}

// タイムライン上のドット（縦線）の色
function dotColorClass(location: AttendanceLocation): string {
  switch (location) {
    case 'CLASSROOM':
      return 'bg-green-400'
    case 'SICK_BAY':
      return 'bg-yellow-400'
    case 'SEPARATE_ROOM':
      return 'bg-blue-400'
    case 'LIBRARY':
      return 'bg-purple-400'
    case 'ONLINE':
      return 'bg-sky-400'
    case 'HOME_LEARNING':
      return 'bg-orange-400'
    case 'OUT_OF_SCHOOL':
      return 'bg-rose-400'
    case 'NOT_APPLICABLE':
    default:
      return 'bg-surface-400'
  }
}

// 時刻または時限の表示文字列
function timeLabel(change: LocationTimelineResponse['changes'][number]): string {
  if (change.changedAtTime) return change.changedAtTime
  if (change.changedAtPeriod !== undefined && change.changedAtPeriod !== null) {
    return t('school.attendance.period.periodNumber', { n: change.changedAtPeriod })
  }
  return '-'
}
</script>

<template>
  <div
    data-testid="location-timeline-card"
    class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900"
  >
    <!-- 現在のロケーション -->
    <div class="px-4 py-3 border-b border-surface-200 dark:border-surface-700">
      <div class="text-xs text-surface-500 mb-1">
        {{ $t('school.location.current') }}
      </div>
      <span
        class="inline-flex items-center px-3 py-1 rounded-full text-sm font-semibold border"
        :class="locationColorClass(timeline.currentLocation)"
      >
        {{ $t(`school.location.${timeline.currentLocation}`) }}
      </span>
    </div>

    <!-- タイムライン本体 -->
    <div class="p-4">
      <div class="text-xs text-surface-500 mb-3">
        {{ $t('school.location.title') }}
      </div>

      <!-- 変化なし -->
      <div
        v-if="timeline.changes.length === 0"
        class="text-center text-surface-400 text-sm py-6"
      >
        {{ $t('school.location.noChanges') }}
      </div>

      <!-- 縦タイムライン -->
      <ol v-else class="relative border-l border-surface-200 dark:border-surface-700 ml-3 space-y-4">
        <li
          v-for="(change, index) in timeline.changes"
          :key="change.id ?? index"
          class="pl-5"
        >
          <!-- ドット -->
          <span
            class="absolute -left-1.5 mt-1 h-3 w-3 rounded-full border-2 border-surface-0 dark:border-surface-900"
            :class="dotColorClass(change.toLocation)"
          />

          <!-- 時刻 / 時限 -->
          <div class="text-xs text-surface-400 mb-0.5">
            {{ timeLabel(change) }}
          </div>

          <!-- from → to -->
          <div class="flex flex-wrap items-center gap-1.5 text-sm">
            <span
              class="inline-flex items-center px-2 py-0.5 rounded border text-xs font-medium"
              :class="locationColorClass(change.fromLocation)"
            >
              {{ $t(`school.location.${change.fromLocation}`) }}
            </span>
            <span class="text-surface-400">→</span>
            <span
              class="inline-flex items-center px-2 py-0.5 rounded border text-xs font-medium"
              :class="locationColorClass(change.toLocation)"
            >
              {{ $t(`school.location.${change.toLocation}`) }}
            </span>
          </div>

          <!-- 理由 -->
          <div class="text-xs text-surface-500 mt-0.5">
            {{ $t(`school.location.reason_${change.reason}`) }}
            <span v-if="change.note" class="ml-1 text-surface-400">— {{ change.note }}</span>
          </div>
        </li>
      </ol>
    </div>
  </div>
</template>
