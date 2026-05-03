<script setup lang="ts">
import type { ScheduleResponse } from '~/types/schedule'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const { listSchedules, getAttendances } = useScheduleApi()
const { captureQuiet } = useErrorReport()

interface AttendanceItem {
  userId: number
  displayName: string
  avatarUrl: string | null
  status: 'YES' | 'NO' | 'MAYBE' | 'PENDING'
  comment: string | null
}

const schedules = ref<ScheduleResponse[]>([])
const loading = ref(false)
const expandedId = ref<number | null>(null)
const attendanceMap = ref<Record<number, AttendanceItem[]>>({})
const attendanceLoading = ref<Record<number, boolean>>({})

async function load() {
  loading.value = true
  schedules.value = []
  try {
    const now = new Date()
    const from = new Date(now.getTime() - 30 * 86400000).toISOString().slice(0, 19)
    const to = new Date(now.getTime() + 30 * 86400000).toISOString().slice(0, 19)
    const res = await listSchedules(props.scopeType, props.scopeId, { from, to, size: 10 })
    schedules.value = (res.data as ScheduleResponse[]).filter((s) => s.attendanceStats !== null)
  } catch (err) {
    captureQuiet(err, { context: 'WidgetAttendanceResults: スケジュール取得' })
  } finally {
    loading.value = false
  }
}

async function toggleExpand(schedule: ScheduleResponse) {
  if (expandedId.value === schedule.id) {
    expandedId.value = null
    return
  }
  expandedId.value = schedule.id
  if (!attendanceMap.value[schedule.id]) {
    attendanceLoading.value = { ...attendanceLoading.value, [schedule.id]: true }
    try {
      const res = await getAttendances(props.scopeType, props.scopeId, schedule.id)
      attendanceMap.value = { ...attendanceMap.value, [schedule.id]: res.data as AttendanceItem[] }
    } catch (err) {
      captureQuiet(err, { context: `WidgetAttendanceResults: 出欠取得 scheduleId=${schedule.id}` })
      attendanceMap.value = { ...attendanceMap.value, [schedule.id]: [] }
    } finally {
      attendanceLoading.value = { ...attendanceLoading.value, [schedule.id]: false }
    }
  }
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleDateString('ja-JP', { month: 'short', day: 'numeric', weekday: 'short' })
}

// 出欠率バーの幅(%)
function barWidth(count: number, total: number): number {
  if (!total) return 0
  return Math.round((count / total) * 100)
}

const statusConfig = {
  YES: { label: '出席', color: '#22c55e', icon: 'pi-check', bg: 'bg-green-100 text-green-700' },
  NO: { label: '欠席', color: '#ef4444', icon: 'pi-times', bg: 'bg-red-100 text-red-600' },
  MAYBE: { label: '未定', color: '#f59e0b', icon: 'pi-question', bg: 'bg-yellow-100 text-yellow-700' },
  PENDING: { label: '未回答', color: '#94a3b8', icon: 'pi-clock', bg: 'bg-surface-100 text-surface-500' },
}

onMounted(load)
</script>

<template>
  <div @click.stop>
    <!-- ヘッダー -->
    <div class="mb-3 flex items-center justify-between">
      <span class="text-xs text-surface-400">直近30日 ± のイベント</span>
      <Button icon="pi pi-refresh" text rounded size="small" :loading="loading" @click="load" />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 4" :key="i" height="4rem" />
    </div>

    <!-- 一覧 -->
    <div v-else-if="schedules.length > 0" class="space-y-2">
      <div
        v-for="schedule in schedules"
        :key="schedule.id"
        class="overflow-hidden rounded-lg border border-surface-300 dark:border-surface-600"
      >
        <!-- サマリー行 -->
        <button
          class="flex w-full items-start gap-3 bg-surface-0 px-3 py-2.5 text-left transition-colors hover:bg-surface-50 dark:bg-surface-800 dark:hover:bg-surface-700/60"
          @click.stop="toggleExpand(schedule)"
        >
          <!-- 日付 -->
          <div class="shrink-0 text-center">
            <p class="text-xs font-semibold text-primary">{{ formatDate(schedule.startAt) }}</p>
          </div>

          <!-- タイトル + 出欠バー -->
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ schedule.title }}
            </p>

            <!-- 積み上げ出欠バー -->
            <div v-if="schedule.attendanceStats" class="mt-1.5">
              <div class="flex h-2 w-full overflow-hidden rounded-full bg-surface-200 dark:bg-surface-600">
                <div
                  class="h-full transition-all"
                  :style="{ width: `${barWidth(schedule.attendanceStats.yes, schedule.attendanceStats.total)}%`, backgroundColor: statusConfig.YES.color }"
                />
                <div
                  class="h-full transition-all"
                  :style="{ width: `${barWidth(schedule.attendanceStats.maybe, schedule.attendanceStats.total)}%`, backgroundColor: statusConfig.MAYBE.color }"
                />
                <div
                  class="h-full transition-all"
                  :style="{ width: `${barWidth(schedule.attendanceStats.no, schedule.attendanceStats.total)}%`, backgroundColor: statusConfig.NO.color }"
                />
              </div>
              <!-- カウント -->
              <div class="mt-1 flex gap-2 text-xs">
                <span class="text-green-600">
                  <i class="pi pi-check text-[10px]" /> {{ schedule.attendanceStats.yes }}
                </span>
                <span class="text-yellow-600">
                  <i class="pi pi-question text-[10px]" /> {{ schedule.attendanceStats.maybe }}
                </span>
                <span class="text-red-500">
                  <i class="pi pi-times text-[10px]" /> {{ schedule.attendanceStats.no }}
                </span>
                <span class="text-surface-400">
                  <i class="pi pi-clock text-[10px]" /> {{ schedule.attendanceStats.pending }}
                </span>
                <span class="text-surface-400">/ {{ schedule.attendanceStats.total }}名</span>
              </div>
            </div>
          </div>

          <!-- 展開アイコン -->
          <i
            class="pi shrink-0 text-xs text-surface-400 transition-transform"
            :class="expandedId === schedule.id ? 'pi-chevron-up' : 'pi-chevron-down'"
          />
        </button>

        <!-- 展開: メンバー別出欠一覧 -->
        <div
          v-if="expandedId === schedule.id"
          class="border-t border-surface-200 bg-surface-50 px-3 py-3 dark:border-surface-600 dark:bg-surface-700/20"
          @click.stop
        >
          <!-- ローディング -->
          <div v-if="attendanceLoading[schedule.id]" class="space-y-1.5 py-1">
            <Skeleton v-for="i in 4" :key="i" height="2rem" />
          </div>

          <!-- メンバー一覧 -->
          <div
            v-else-if="attendanceMap[schedule.id]?.length"
            class="grid grid-cols-1 gap-1 sm:grid-cols-2"
          >
            <div
              v-for="member in attendanceMap[schedule.id]"
              :key="member.userId"
              class="flex items-center gap-2 rounded bg-white px-2 py-1.5 dark:bg-surface-800"
            >
              <!-- アバター -->
              <div
                class="flex h-6 w-6 shrink-0 items-center justify-center overflow-hidden rounded-full bg-surface-200 text-xs font-semibold dark:bg-surface-600"
              >
                <img
                  v-if="member.avatarUrl"
                  :src="member.avatarUrl"
                  :alt="member.displayName"
                  class="h-full w-full object-cover"
                >
                <span v-else>{{ member.displayName.charAt(0) }}</span>
              </div>
              <!-- 名前 -->
              <span class="min-w-0 flex-1 truncate text-xs text-surface-600 dark:text-surface-300">
                {{ member.displayName }}
              </span>
              <!-- ステータスバッジ -->
              <span
                class="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium"
                :class="statusConfig[member.status].bg"
              >
                {{ statusConfig[member.status].label }}
              </span>
            </div>
          </div>

          <!-- データなし -->
          <div v-else class="py-3 text-center text-sm text-surface-400">
            出欠データがありません
          </div>
        </div>
      </div>
    </div>

    <!-- 空状態 -->
    <div v-else class="py-8 text-center">
      <i class="pi pi-calendar-times mb-2 text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">対象のイベントがありません</p>
    </div>
  </div>
</template>
