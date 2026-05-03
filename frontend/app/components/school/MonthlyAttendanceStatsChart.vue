<script setup lang="ts">
import { computed } from 'vue'
import type { MonthlyStatisticsResponse } from '~/types/school'

const props = defineProps<{
  stats: MonthlyStatisticsResponse
}>()

const { t } = useI18n()

const attendancePercent = computed(() => {
  if (props.stats.totalStudents === 0) return 0
  return Math.round((props.stats.presentCount / props.stats.totalStudents) * 100)
})

const absentPercent = computed(() => {
  if (props.stats.totalStudents === 0) return 0
  return Math.round((props.stats.absentCount / props.stats.totalStudents) * 100)
})

const undecidedPercent = computed(() => {
  return Math.max(0, 100 - attendancePercent.value - absentPercent.value)
})

// Chart.js データ（PrimeVue Chart コンポーネント用）
const chartData = computed(() => ({
  labels: [
    t('school.statistics.presentCount'),
    t('school.statistics.absentCount'),
    t('school.attendance.summary.undecided'),
  ],
  datasets: [
    {
      data: [props.stats.presentCount, props.stats.absentCount, props.stats.undecidedCount],
      backgroundColor: ['#22c55e', '#ef4444', '#94a3b8'],
      hoverBackgroundColor: ['#16a34a', '#dc2626', '#64748b'],
    },
  ],
}))

const chartOptions = computed(() => ({
  responsive: true,
  plugins: {
    legend: {
      position: 'bottom',
    },
    tooltip: {
      callbacks: {
        label: (context: { label: string; raw: number }) => `${context.label}: ${context.raw}人`,
      },
    },
  },
}))

// Chart コンポーネントが利用可能か確認する
const useChartComponent = computed(() => {
  try {
    return typeof resolveComponent('Chart') !== 'string'
  } catch {
    return false
  }
})
</script>

<template>
  <div class="monthly-attendance-stats-chart" data-testid="stats-chart">
    <!-- サマリーカード -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
      <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4 text-center">
        <div class="text-xs text-surface-500 mb-1">
          {{ $t('school.statistics.totalStudents') }}
        </div>
        <div class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ stats.totalStudents }}
        </div>
      </div>
      <div class="rounded-lg border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-950 p-4 text-center">
        <div class="text-xs text-green-600 dark:text-green-400 mb-1">
          {{ $t('school.statistics.presentCount') }}
        </div>
        <div class="text-2xl font-bold text-green-700 dark:text-green-300" data-testid="stats-present-count">
          {{ stats.presentCount }}
        </div>
      </div>
      <div class="rounded-lg border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-950 p-4 text-center">
        <div class="text-xs text-red-600 dark:text-red-400 mb-1">
          {{ $t('school.statistics.absentCount') }}
        </div>
        <div class="text-2xl font-bold text-red-700 dark:text-red-300" data-testid="stats-absent-count">
          {{ stats.absentCount }}
        </div>
      </div>
      <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4 text-center">
        <div class="text-xs text-surface-500 mb-1">
          {{ $t('school.statistics.attendanceRate') }}
        </div>
        <div class="text-2xl font-bold text-primary-600 dark:text-primary-400" data-testid="statistics-attendance-rate">
          {{ stats.attendanceRate.toFixed(1) }}%
        </div>
      </div>
    </div>

    <!-- グラフ -->
    <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4 mb-6">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-300 mb-4">
        {{ $t('school.statistics.monthly') }} ({{ stats.year }}/{{ stats.month }})
      </h3>

      <!-- PrimeVue Chart が使用可能な場合 -->
      <template v-if="useChartComponent">
        <Chart type="doughnut" :data="chartData" :options="chartOptions" class="max-w-xs mx-auto" />
      </template>

      <!-- フォールバック: シンプルなバー表示 -->
      <template v-else>
        <div class="space-y-3">
          <div>
            <div class="flex justify-between text-sm mb-1">
              <span class="text-green-600 dark:text-green-400">
                {{ $t('school.statistics.presentCount') }}
              </span>
              <span class="font-medium">{{ attendancePercent }}%</span>
            </div>
            <div class="w-full bg-surface-100 dark:bg-surface-800 rounded-full h-3">
              <div
                class="bg-green-500 h-3 rounded-full transition-all"
                :style="{ width: `${attendancePercent}%` }"
              />
            </div>
          </div>
          <div>
            <div class="flex justify-between text-sm mb-1">
              <span class="text-red-600 dark:text-red-400">
                {{ $t('school.statistics.absentCount') }}
              </span>
              <span class="font-medium">{{ absentPercent }}%</span>
            </div>
            <div class="w-full bg-surface-100 dark:bg-surface-800 rounded-full h-3">
              <div
                class="bg-red-500 h-3 rounded-full transition-all"
                :style="{ width: `${absentPercent}%` }"
              />
            </div>
          </div>
          <div>
            <div class="flex justify-between text-sm mb-1">
              <span class="text-surface-500">
                {{ $t('school.attendance.summary.undecided') }}
              </span>
              <span class="font-medium">{{ undecidedPercent }}%</span>
            </div>
            <div class="w-full bg-surface-100 dark:bg-surface-800 rounded-full h-3">
              <div
                class="bg-surface-400 h-3 rounded-full transition-all"
                :style="{ width: `${undecidedPercent}%` }"
              />
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 生徒別内訳テーブル -->
    <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 overflow-hidden">
      <div class="px-4 py-3 border-b border-surface-200 dark:border-surface-700">
        <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-300">
          {{ $t('school.attendance.label.student') }}
        </h3>
      </div>

      <div v-if="stats.studentBreakdown.length === 0" class="text-center text-surface-400 py-8 text-sm">
        {{ $t('school.statistics.noData') }}
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-surface-50 dark:bg-surface-800">
            <tr>
              <th class="text-left px-4 py-2 text-surface-500 font-medium">
                ID
              </th>
              <th class="text-right px-4 py-2 text-surface-500 font-medium">
                {{ $t('school.statistics.presentCount') }}
              </th>
              <th class="text-right px-4 py-2 text-surface-500 font-medium">
                {{ $t('school.statistics.absentCount') }}
              </th>
              <th class="text-right px-4 py-2 text-surface-500 font-medium">
                {{ $t('school.statistics.lateCount') }}
              </th>
              <th class="text-right px-4 py-2 text-surface-500 font-medium">
                {{ $t('school.statistics.earlyLeaveCount') }}
              </th>
              <th class="text-right px-4 py-2 text-surface-500 font-medium">
                {{ $t('school.statistics.attendanceRate') }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="student in stats.studentBreakdown"
              :key="student.studentUserId"
              class="border-t border-surface-100 dark:border-surface-800 hover:bg-surface-50 dark:hover:bg-surface-800 transition-colors"
            >
              <td class="px-4 py-2 text-surface-700 dark:text-surface-300">
                {{ student.studentUserId }}
              </td>
              <td class="text-right px-4 py-2 text-green-600 dark:text-green-400 font-medium">
                {{ student.presentDays }}
              </td>
              <td class="text-right px-4 py-2 text-red-600 dark:text-red-400 font-medium">
                {{ student.absentDays }}
              </td>
              <td class="text-right px-4 py-2 text-yellow-600 dark:text-yellow-400">
                {{ student.lateCount }}
              </td>
              <td class="text-right px-4 py-2 text-orange-600 dark:text-orange-400">
                {{ student.earlyLeaveCount }}
              </td>
              <td class="text-right px-4 py-2 font-semibold">
                <span
                  :class="student.attendanceRate >= 90
                    ? 'text-green-600 dark:text-green-400'
                    : student.attendanceRate >= 75
                      ? 'text-yellow-600 dark:text-yellow-400'
                      : 'text-red-600 dark:text-red-400'"
                >
                  {{ student.attendanceRate.toFixed(1) }}%
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
