<script setup lang="ts">
import type { GanttTodo } from '~/types/todo'
import { toLocalDateString } from '~/utils/localDate'

const { t } = useI18n()
const { getHoliday } = useHolidays()

const props = defineProps<{
  todos: GanttTodo[]
  fromDate: string
  toDate: string
  currentYear: number
  currentMonth: number
}>()

const emit = defineEmits<{
  todoClick: [id: number]
  prevMonth: []
  nextMonth: []
}>()

/** 2つの日付文字列（yyyy-MM-dd）の差分日数を計算する */
function dateDiffDays(from: string, to: string): number {
  const f = new Date(from)
  const t = new Date(to)
  return Math.round((t.getTime() - f.getTime()) / 86400000)
}

/** ガント全体の日数（toDate - fromDate + 1） */
const totalDays = computed(() => dateDiffDays(props.fromDate, props.toDate) + 1)

/**
 * バーの左位置（%）: (startDate - fromDate) / totalDays * 100
 * クランプ: 0〜100
 */
function barLeft(todo: GanttTodo): string {
  const offset = dateDiffDays(props.fromDate, todo.startDate)
  const pct = Math.max(0, Math.min(100, (offset / totalDays.value) * 100))
  return `${pct.toFixed(2)}%`
}

/**
 * バーの幅（%）: (dueDate - startDate + 1) / totalDays * 100
 * 最小 0.5%（視認できる最小幅）
 */
function barWidth(todo: GanttTodo): string {
  const span = dateDiffDays(todo.startDate, todo.dueDate) + 1
  const pct = Math.max(0.5, Math.min(100, (span / totalDays.value) * 100))
  return `${pct.toFixed(2)}%`
}

/** バーの色（ステータス別） */
function barBgClass(todo: GanttTodo): string {
  if (todo.status === 'COMPLETED') return 'bg-emerald-400 dark:bg-emerald-500'
  if (todo.priority === 'URGENT') return 'bg-red-500'
  if (todo.priority === 'HIGH') return 'bg-orange-400'
  if (todo.priority === 'MEDIUM') return 'bg-blue-500'
  return 'bg-green-500'
}

/** 進捗充填バーの幅（%） */
function progressFillWidth(progressRate: string): string {
  const rate = parseFloat(progressRate)
  return `${Math.min(100, Math.max(0, rate)).toFixed(2)}%`
}

/** 表示範囲が単一月かどうか */
const isSingleMonth = computed(() => {
  const f = new Date(props.fromDate)
  const t = new Date(props.toDate)
  return f.getFullYear() === t.getFullYear() && f.getMonth() === t.getMonth()
})

/** 日付ヘッダー用の日付配列（fromDate から toDate まで） */
const dateHeaders = computed<Array<{ date: string; label: string; colorClass: string }>>(() => {
  const result: Array<{ date: string; label: string; colorClass: string }> = []
  const from = new Date(props.fromDate)
  const to = new Date(props.toDate)
  const cur = new Date(from)
  while (cur <= to) {
    const m = cur.getMonth() + 1
    const d = cur.getDate()
    const dow = cur.getDay() // 0=日, 6=土
    const dateStr = toLocalDateString(cur)
    const label = isSingleMonth.value ? `${d}` : (d === 1 ? `${m}/${d}` : `${d}`)
    const isHoliday = !!getHoliday(dateStr)
    const colorClass = (dow === 0 || isHoliday)
      ? 'bg-red-50 text-red-500 dark:bg-red-900/20 dark:text-red-400'
      : dow === 6
        ? 'bg-sky-100 text-sky-600 dark:bg-sky-900/30 dark:text-sky-400'
        : 'text-surface-400 dark:text-surface-500'
    result.push({ date: dateStr, label, colorClass })
    cur.setDate(cur.getDate() + 1)
  }
  return result
})

/** ヘッダー各セルの幅（%） */
const headerCellWidth = computed(() => `${(100 / totalDays.value).toFixed(4)}%`)
</script>

<template>
  <div>
    <div class="overflow-x-auto rounded-xl border border-surface-400 dark:border-surface-500">
      <!-- ガントテーブル -->
      <div class="min-w-max">

        <!-- ヘッダー行（日付） -->
        <div class="flex border-b border-primary/20 bg-primary/5 dark:border-primary/20 dark:bg-primary/10">
          <!-- タイトル列のヘッダー（月ナビ） -->
          <div class="flex w-56 shrink-0 items-center justify-between border-r border-primary/20 px-2 py-1.5 dark:border-primary/20">
            <button
              type="button"
              class="rounded p-1 text-surface-400 hover:bg-primary/10 hover:text-primary dark:text-surface-500"
              @click="emit('prevMonth')"
            >
              <i class="pi pi-chevron-left text-xs" />
            </button>
            <span class="text-xs font-semibold text-primary dark:text-primary">
              {{ currentYear }}年{{ currentMonth }}月
            </span>
            <button
              type="button"
              class="rounded p-1 text-surface-400 hover:bg-primary/10 hover:text-primary dark:text-surface-500"
              @click="emit('nextMonth')"
            >
              <i class="pi pi-chevron-right text-xs" />
            </button>
          </div>
          <!-- 日付ヘッダーエリア -->
          <div class="relative flex flex-1">
            <div
              v-for="h in dateHeaders"
              :key="h.date"
              class="shrink-0 border-r border-surface-300 px-0.5 py-2 text-center text-[10px] dark:border-surface-500"
              :class="h.colorClass"
              :style="{ width: headerCellWidth }"
            >
              {{ h.label }}
            </div>
          </div>
        </div>

        <!-- TODO 行 -->
        <div
          v-for="todo in todos"
          :key="todo.id"
          class="flex cursor-pointer border-b border-surface-300 hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800/50"
          :class="{ 'opacity-75': todo.status === 'COMPLETED' }"
          @click="emit('todoClick', todo.id)"
        >
          <!-- タイトル列 -->
          <div
            class="flex w-56 shrink-0 items-center gap-1 border-r border-surface-400 px-2 py-2 dark:border-surface-500"
            :style="{ paddingLeft: `${8 + todo.depth * 16}px` }"
          >
            <!-- 深さインジケーター -->
            <span v-if="todo.depth > 0" class="text-surface-300 dark:text-surface-600">└</span>
            <span class="truncate text-xs text-surface-700 dark:text-surface-300" :title="todo.title">
              {{ todo.title }}
            </span>
          </div>

          <!-- バーエリア -->
          <div class="relative flex-1 py-2">
            <!-- 日付区切り点線（ヘッダーと同幅のセルを重ねる） -->
            <div class="absolute inset-0 flex pointer-events-none">
              <div
                v-for="h in dateHeaders"
                :key="h.date"
                class="shrink-0 h-full border-r border-dashed border-surface-400 dark:border-surface-500"
                :style="{ width: headerCellWidth }"
              />
            </div>
            <div class="relative h-5" style="width: 100%">
              <!-- ガントバー -->
              <div
                class="absolute top-0 flex h-5 items-center overflow-hidden rounded"
                :class="barBgClass(todo)"
                :style="{ left: barLeft(todo), width: barWidth(todo) }"
              >
                <!-- 進捗充填 -->
                <div
                  class="absolute left-0 top-0 h-full bg-white/35"
                  :class="parseFloat(todo.progressRate) >= 100 ? 'rounded' : 'rounded-l'"
                  :style="{ width: progressFillWidth(todo.progressRate) }"
                />
                <!-- 進捗率テキスト -->
                <span class="relative z-10 px-1 text-[10px] font-semibold text-white leading-none whitespace-nowrap">
                  {{ parseFloat(todo.progressRate).toFixed(0) }}%
                </span>
              </div>
            </div>
          </div>
        </div>

      </div>

      <!-- データなし -->
      <div
        v-if="todos.length === 0"
        class="flex items-center justify-center py-16"
      >
        <p class="text-sm text-surface-400">{{ t('todo.enhancement.gantt.no_data') }}</p>
      </div>
    </div>
  </div>
</template>
