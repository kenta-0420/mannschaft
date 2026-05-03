<script setup lang="ts">
import { computed, ref, onMounted, watch } from 'vue'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const { t } = useI18n()

const { monthlyStats, termStats, loadingMonthly, loadingTerm, exporting, loadMonthlyStatistics, loadTermStatistics, downloadCsv } =
  useAttendanceStatistics(teamId)

const today = new Date()
const selectedYear = ref(today.getFullYear())
const selectedMonth = ref(today.getMonth() + 1)

const termFrom = ref(
  new Date(today.getFullYear(), today.getMonth() - 2, 1).toISOString().slice(0, 10),
)
const termTo = ref(today.toISOString().slice(0, 10))

const activeTab = ref<'monthly' | 'term'>('monthly')

const yearOptions = Array.from({ length: 5 }, (_, i) => ({
  value: today.getFullYear() - 2 + i,
  label: String(today.getFullYear() - 2 + i),
}))

const monthOptions = Array.from({ length: 12 }, (_, i) => ({
  value: i + 1,
  label: String(i + 1),
}))

async function loadMonthly(): Promise<void> {
  await loadMonthlyStatistics(selectedYear.value, selectedMonth.value)
}

async function loadTerm(): Promise<void> {
  await loadTermStatistics(termFrom.value, termTo.value)
}

function onExportCsv(): void {
  downloadCsv(termFrom.value, termTo.value)
}

watch([selectedYear, selectedMonth], () => {
  if (activeTab.value === 'monthly') {
    void loadMonthly()
  }
})

onMounted(() => {
  void loadMonthly()
})
</script>

<template>
  <div class="flex flex-col min-h-screen" data-testid="statistics-page">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.statistics.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-4xl mx-auto w-full">
      <!-- タブ切り替え -->
      <div class="flex gap-2 mb-6 border-b border-surface-200 dark:border-surface-700">
        <button
          type="button"
          class="px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px"
          :class="activeTab === 'monthly'
            ? 'border-primary-500 text-primary-600 dark:text-primary-400'
            : 'border-transparent text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'"
          data-testid="statistics-tab-monthly"
          @click="activeTab = 'monthly'"
        >
          {{ $t('school.statistics.monthly') }}
        </button>
        <button
          type="button"
          class="px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px"
          :class="activeTab === 'term'
            ? 'border-primary-500 text-primary-600 dark:text-primary-400'
            : 'border-transparent text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'"
          data-testid="statistics-tab-term"
          @click="activeTab = 'term'"
        >
          {{ $t('school.statistics.term') }}
        </button>
      </div>

      <!-- 月次統計タブ -->
      <template v-if="activeTab === 'monthly'">
        <div class="flex flex-wrap gap-3 mb-4">
          <div>
            <label class="text-xs text-surface-500 mb-1 block">
              {{ $t('school.statistics.year') }}
            </label>
            <Select
              v-model="selectedYear"
              :options="yearOptions"
              option-label="label"
              option-value="value"
              class="w-28"
              data-testid="statistics-year"
            />
          </div>
          <div>
            <label class="text-xs text-surface-500 mb-1 block">
              {{ $t('school.statistics.month') }}
            </label>
            <Select
              v-model="selectedMonth"
              :options="monthOptions"
              option-label="label"
              option-value="value"
              class="w-20"
              data-testid="statistics-month"
            />
          </div>
          <div class="flex items-end">
            <Button
              :label="$t('common.search')"
              :loading="loadingMonthly"
              size="small"
              @click="loadMonthly"
            />
          </div>
        </div>

        <PageLoading v-if="loadingMonthly" />

        <div v-else-if="!monthlyStats" class="text-center text-surface-400 text-sm py-12" data-testid="statistics-no-data">
          {{ $t('school.statistics.noData') }}
        </div>

        <MonthlyAttendanceStatsChart v-else :stats="monthlyStats" />
      </template>

      <!-- 期間別統計タブ -->
      <template v-else>
        <div class="flex flex-wrap gap-3 mb-4 items-end">
          <div>
            <label class="text-xs text-surface-500 mb-1 block">
              {{ $t('school.statistics.from') }}
            </label>
            <InputText v-model="termFrom" type="date" class="w-full" />
          </div>
          <div>
            <label class="text-xs text-surface-500 mb-1 block">
              {{ $t('school.statistics.to') }}
            </label>
            <InputText v-model="termTo" type="date" class="w-full" />
          </div>
          <Button
            :label="$t('common.search')"
            :loading="loadingTerm"
            size="small"
            @click="loadTerm"
          />
          <Button
            :label="$t('school.statistics.exportCsv')"
            :loading="exporting"
            severity="secondary"
            size="small"
            data-testid="statistics-export-csv"
            @click="onExportCsv"
          />
        </div>

        <PageLoading v-if="loadingTerm" />

        <div v-else-if="!termStats" class="text-center text-surface-400 text-sm py-12">
          {{ $t('school.statistics.noData') }}
        </div>

        <template v-else>
          <!-- 期間別サマリー -->
          <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4 text-center">
              <div class="text-xs text-surface-500 mb-1">
                {{ $t('school.statistics.totalSchoolDays') }}
              </div>
              <div class="text-2xl font-bold text-surface-800 dark:text-surface-100">
                {{ termStats.totalSchoolDays }}
              </div>
            </div>
            <div class="rounded-lg border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-950 p-4 text-center">
              <div class="text-xs text-green-600 dark:text-green-400 mb-1">
                {{ $t('school.statistics.presentCount') }}
              </div>
              <div class="text-2xl font-bold text-green-700 dark:text-green-300">
                {{ termStats.presentDays }}
              </div>
            </div>
            <div class="rounded-lg border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-950 p-4 text-center">
              <div class="text-xs text-red-600 dark:text-red-400 mb-1">
                {{ $t('school.statistics.absentCount') }}
              </div>
              <div class="text-2xl font-bold text-red-700 dark:text-red-300">
                {{ termStats.absentDays }}
              </div>
            </div>
            <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4 text-center">
              <div class="text-xs text-surface-500 mb-1">
                {{ $t('school.statistics.attendanceRate') }}
              </div>
              <div class="text-2xl font-bold text-primary-600 dark:text-primary-400">
                {{ termStats.attendanceRate.toFixed(1) }}%
              </div>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-4 mb-6">
            <div class="rounded-lg border border-yellow-200 dark:border-yellow-800 bg-yellow-50 dark:bg-yellow-950 p-4 text-center">
              <div class="text-xs text-yellow-600 dark:text-yellow-400 mb-1">
                {{ $t('school.statistics.lateCount') }}
              </div>
              <div class="text-xl font-bold text-yellow-700 dark:text-yellow-300">
                {{ termStats.lateCount }}
              </div>
            </div>
            <div class="rounded-lg border border-orange-200 dark:border-orange-800 bg-orange-50 dark:bg-orange-950 p-4 text-center">
              <div class="text-xs text-orange-600 dark:text-orange-400 mb-1">
                {{ $t('school.statistics.earlyLeaveCount') }}
              </div>
              <div class="text-xl font-bold text-orange-700 dark:text-orange-300">
                {{ termStats.earlyLeaveCount }}
              </div>
            </div>
          </div>

          <!-- 教科別内訳 -->
          <div
            v-if="termStats.subjectBreakdown.length > 0"
            class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 overflow-hidden"
          >
            <div class="px-4 py-3 border-b border-surface-200 dark:border-surface-700">
              <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-300">
                {{ $t('school.statistics.subjectBreakdown') }}
              </h3>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead class="bg-surface-50 dark:bg-surface-800">
                  <tr>
                    <th class="text-left px-4 py-2 text-surface-500 font-medium">
                      教科
                    </th>
                    <th class="text-right px-4 py-2 text-surface-500 font-medium">
                      {{ $t('school.statistics.presentCount') }}
                    </th>
                    <th class="text-right px-4 py-2 text-surface-500 font-medium">
                      合計時限
                    </th>
                    <th class="text-right px-4 py-2 text-surface-500 font-medium">
                      {{ $t('school.statistics.attendanceRate') }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="subject in termStats.subjectBreakdown"
                    :key="subject.subjectName"
                    class="border-t border-surface-100 dark:border-surface-800 hover:bg-surface-50 dark:hover:bg-surface-800 transition-colors"
                  >
                    <td class="px-4 py-2 font-medium text-surface-800 dark:text-surface-100">
                      {{ subject.subjectName }}
                    </td>
                    <td class="text-right px-4 py-2 text-green-600 dark:text-green-400">
                      {{ subject.presentPeriods }}
                    </td>
                    <td class="text-right px-4 py-2 text-surface-600 dark:text-surface-400">
                      {{ subject.totalPeriods }}
                    </td>
                    <td class="text-right px-4 py-2 font-semibold">
                      <span
                        :class="subject.attendanceRate >= 90
                          ? 'text-green-600 dark:text-green-400'
                          : subject.attendanceRate >= 75
                            ? 'text-yellow-600 dark:text-yellow-400'
                            : 'text-red-600 dark:text-red-400'"
                      >
                        {{ subject.attendanceRate.toFixed(1) }}%
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
      </template>
    </main>
  </div>
</template>
