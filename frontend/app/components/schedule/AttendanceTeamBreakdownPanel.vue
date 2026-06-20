<script setup lang="ts">
// F03.1 (B) 組織出欠のチーム別内訳（by_team）表示パネル。
// - 認可: 組織 ADMIN 限定 EP（/organizations/{orgPublicId}/schedules/{scheduleId}/attendances/team-breakdown）。
//   呼び出し側（EventDetailPanel）で 組織スコープ + 管理者(canEdit) のときのみ描画する。
//   万一 403 が返った場合は症状を隠さず「権限がありません」を表示する。
// - チームごとに 出席/一部参加/欠席/未回答 のカウントを表示。teamId=null は「組織直接メンバー」。
// - CSV エクスポート（ADMIN 限定）を提供する。
import { useScheduleAttendance } from '~/composables/schedule/useScheduleAttendance'
import type { AttendanceTeamBreakdownResponse } from '~/composables/schedule/useScheduleAttendance'

const props = defineProps<{
  // 組織の public id / slug（EP の orgPublicId）
  orgPublicId: string
  scheduleId: number
}>()

const { t } = useI18n()
const { getAttendanceTeamBreakdown, exportAttendanceTeamBreakdownCsv } = useScheduleAttendance()
const { error: showError } = useNotification()

const data = ref<AttendanceTeamBreakdownResponse | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const forbidden = ref(false)
const exporting = ref(false)

async function load() {
  loading.value = true
  fetchFailed.value = false
  forbidden.value = false
  try {
    const res = await getAttendanceTeamBreakdown(props.orgPublicId, props.scheduleId)
    data.value = res.data ?? null
  } catch (e) {
    const status = e as { statusCode?: number; response?: { status?: number } }
    const code = status.statusCode ?? status.response?.status
    if (code === 403) {
      forbidden.value = true
    } else {
      fetchFailed.value = true
    }
  } finally {
    loading.value = false
  }
}

const teams = computed(() => data.value?.byTeam ?? [])
const total = computed(() => data.value?.total ?? null)

function teamLabel(teamId: number | null | undefined, teamName: string | null | undefined): string {
  return teamId == null ? t('schedule.attendanceTeamBreakdown.directMembers') : (teamName ?? '')
}

async function downloadCsv() {
  if (exporting.value) return
  exporting.value = true
  try {
    const blob = (await exportAttendanceTeamBreakdownCsv(props.orgPublicId, props.scheduleId)) as Blob
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `attendance-team-breakdown-${props.scheduleId}.csv`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  } catch {
    showError(t('schedule.attendanceTeamBreakdown.exportFailed'))
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="flex flex-col gap-3" data-testid="attendance-team-breakdown-panel">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('schedule.attendanceTeamBreakdown.title') }}
      </h3>
      <div class="flex items-center gap-2">
        <Button
          v-if="!loading && !forbidden && !fetchFailed && teams.length > 0"
          :label="t('schedule.attendanceTeamBreakdown.exportCsv')"
          icon="pi pi-download"
          size="small"
          outlined
          :loading="exporting"
          data-testid="attendance-team-breakdown-export"
          @click="downloadCsv"
        />
        <Button
          :label="t('schedule.attendanceTeamBreakdown.reload')"
          icon="pi pi-refresh"
          size="small"
          text
          :loading="loading"
          data-testid="attendance-team-breakdown-refresh"
          @click="load"
        />
      </div>
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-6">
      <LoadingBounce />
    </div>

    <!-- 権限なし（403） -->
    <div
      v-else-if="forbidden"
      class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-5 text-center dark:border-surface-600 dark:bg-surface-800/60"
      data-testid="attendance-team-breakdown-forbidden"
    >
      <i class="pi pi-lock text-xl text-surface-400" />
      <p class="text-sm text-surface-500 dark:text-surface-300">
        {{ t('schedule.attendanceTeamBreakdown.forbidden') }}
      </p>
    </div>

    <!-- 取得失敗 -->
    <div
      v-else-if="fetchFailed"
      class="flex flex-col items-center gap-2 rounded-lg border border-red-200 bg-red-50 p-5 text-center dark:border-red-700 dark:bg-red-900/20"
    >
      <i class="pi pi-exclamation-triangle text-xl text-red-500" />
      <p class="text-sm text-red-700 dark:text-red-200">
        {{ t('schedule.attendanceTeamBreakdown.fetchFailed') }}
      </p>
      <Button :label="t('schedule.attendanceTeamBreakdown.retry')" icon="pi pi-refresh" size="small" @click="load" />
    </div>

    <!-- 空（トグル OFF など） -->
    <div
      v-else-if="teams.length === 0"
      class="rounded-lg border border-dashed border-surface-300 bg-surface-50 p-5 text-center text-sm text-surface-400 dark:border-surface-600 dark:bg-surface-800/40"
      data-testid="attendance-team-breakdown-empty"
    >
      {{ t('schedule.attendanceTeamBreakdown.empty') }}
    </div>

    <!-- 内訳テーブル -->
    <div v-else class="overflow-x-auto">
      <table class="w-full text-sm" data-testid="attendance-team-breakdown-table">
        <thead>
          <tr class="border-b border-surface-200 text-xs text-surface-500 dark:border-surface-700 dark:text-surface-400">
            <th class="py-1 text-left font-medium">{{ t('schedule.attendanceTeamBreakdown.team') }}</th>
            <th class="py-1 text-right font-medium">{{ t('schedule.attendanceTeamBreakdown.attending') }}</th>
            <th class="py-1 text-right font-medium">{{ t('schedule.attendanceTeamBreakdown.partial') }}</th>
            <th class="py-1 text-right font-medium">{{ t('schedule.attendanceTeamBreakdown.absent') }}</th>
            <th class="py-1 text-right font-medium">{{ t('schedule.attendanceTeamBreakdown.undecided') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(team, ti) in teams"
            :key="team.teamId ?? `direct-${ti}`"
            class="border-b border-surface-100 dark:border-surface-800"
            :data-testid="`attendance-team-breakdown-row-${team.teamId ?? 'direct'}`"
          >
            <td class="py-1.5 text-surface-700 dark:text-surface-200">
              {{ teamLabel(team.teamId, team.teamName) }}
            </td>
            <td class="py-1.5 text-right text-green-600 dark:text-green-300">{{ team.attending }}</td>
            <td class="py-1.5 text-right text-amber-600 dark:text-amber-300">{{ team.partial }}</td>
            <td class="py-1.5 text-right text-red-600 dark:text-red-300">{{ team.absent }}</td>
            <td class="py-1.5 text-right text-surface-400">{{ team.undecided }}</td>
          </tr>
        </tbody>
        <tfoot v-if="total">
          <tr class="border-t border-surface-300 text-xs font-semibold dark:border-surface-600">
            <td class="py-1.5 text-surface-600 dark:text-surface-300">
              {{ t('schedule.attendanceTeamBreakdown.total') }}
            </td>
            <td class="py-1.5 text-right">{{ total.attending }}</td>
            <td class="py-1.5 text-right">{{ total.partial }}</td>
            <td class="py-1.5 text-right">{{ total.absent }}</td>
            <td class="py-1.5 text-right">{{ total.undecided }}</td>
          </tr>
        </tfoot>
      </table>
      <p class="mt-1 text-xs text-surface-400">
        {{ t('schedule.attendanceTeamBreakdown.totalNote') }}
      </p>
    </div>
  </section>
</template>
