<script setup lang="ts">
/**
 * 時間割 — 週間ビュータブ
 *
 * 親 (teams/[id]/timetable.vue) から渡された WeeklyView を表に描画する。
 * 時間割選択ボタン・週ナビ・時限グリッド・「臨時変更を追加」ボタンを内包し、
 * ユーザー操作は emits で親に伝える。API 呼び出しは親に集約。
 */
import type { Timetable, WeeklyView, DayOfWeekKey } from '~/types/timetable'

defineProps<{
  timetables: Timetable[]
  selectedTimetable: Timetable | null
  weeklyView: WeeklyView | null
  canManage: boolean
  dayKeys: readonly DayOfWeekKey[]
  dayLabels: Record<DayOfWeekKey, string>
}>()

const emit = defineEmits<{
  (e: 'select', timetable: Timetable): void
  (e: 'navigate', direction: 'prev' | 'next' | 'current'): void
  (e: 'open-change-dialog'): void
}>()
</script>

<template>
  <div>
    <!-- 時間割選択 -->
    <div v-if="timetables.length > 1" class="mb-4 flex flex-wrap gap-2">
      <Button
        v-for="tt in timetables.filter((t) => t.status !== 'ARCHIVED')"
        :key="tt.id"
        :label="tt.name"
        :severity="selectedTimetable?.id === tt.id ? 'primary' : 'secondary'"
        size="small"
        @click="emit('select', tt)"
      />
    </div>

    <div v-if="weeklyView">
      <!-- 週ナビゲーション -->
      <div class="mb-3 flex items-center justify-between">
        <div class="flex items-center gap-2">
          <Button
            icon="pi pi-chevron-left"
            severity="secondary"
            size="small"
            :aria-label="$t('timetable.prev_week')"
            @click="emit('navigate', 'prev')"
          />
          <span class="text-sm font-medium">
            {{ weeklyView.weekStart }} 〜 {{ weeklyView.weekEnd }}
          </span>
          <Button
            icon="pi pi-chevron-right"
            severity="secondary"
            size="small"
            :aria-label="$t('timetable.next_week')"
            @click="emit('navigate', 'next')"
          />
        </div>
        <div class="flex items-center gap-2">
          <Badge
            v-if="weeklyView.weekPatternEnabled"
            :value="
              weeklyView.currentWeekPattern === 'A'
                ? $t('timetable.week_pattern_a')
                : $t('timetable.week_pattern_b')
            "
            severity="info"
          />
          <Button
            :label="$t('timetable.current_week')"
            severity="secondary"
            size="small"
            @click="emit('navigate', 'current')"
          />
        </div>
      </div>

      <!-- 時間割グリッド -->
      <div class="overflow-x-auto">
        <table class="w-full border-collapse text-sm">
          <thead>
            <tr>
              <th
                class="border border-surface-300 bg-surface-50 p-2 dark:border-surface-600 dark:bg-surface-800"
              >
                {{ $t('timetable.period_number') }}
              </th>
              <th
                v-for="key in dayKeys"
                :key="key"
                class="border border-surface-300 bg-surface-50 p-2 text-center dark:border-surface-600 dark:bg-surface-800"
                :class="{ 'bg-red-50 dark:bg-red-900/20': weeklyView.days[key]?.isDayOff }"
              >
                <div>{{ dayLabels[key] }}</div>
                <div v-if="weeklyView.days[key]" class="text-xs text-surface-400">
                  {{ weeklyView.days[key].date?.slice(5) }}
                </div>
                <div
                  v-if="weeklyView.days[key]?.isDayOff"
                  class="text-xs font-medium text-red-500"
                >
                  {{ $t('timetable.day_off') }}
                </div>
              </th>
            </tr>
          </thead>
          <tbody>
            <!-- スロット行 - periodごと -->
            <template v-if="weeklyView.periods.length > 0">
              <tr v-for="period in weeklyView.periods" :key="period.periodNumber">
                <td
                  class="border border-surface-300 bg-surface-50 p-2 text-center dark:border-surface-600 dark:bg-surface-800"
                >
                  <div class="font-medium">{{ period.label }}</div>
                  <div class="text-xs text-surface-400">
                    {{ period.startTime }}-{{ period.endTime }}
                  </div>
                </td>
                <td
                  v-for="key in dayKeys"
                  :key="key"
                  class="border border-surface-300 p-1 align-top dark:border-surface-600"
                  :class="{ 'bg-surface-100 dark:bg-surface-800': weeklyView.days[key]?.isDayOff }"
                >
                  <template v-if="!weeklyView.days[key]?.isDayOff">
                    <template
                      v-for="slot in (weeklyView.days[key]?.slots ?? []).filter(
                        (s) => s.periodNumber === period.periodNumber,
                      )"
                      :key="slot.periodNumber"
                    >
                      <div
                        class="rounded p-1"
                        :style="slot.color ? { backgroundColor: slot.color + '20' } : {}"
                      >
                        <p
                          class="font-medium"
                          :class="
                            slot.changeType === 'CANCEL'
                              ? 'line-through text-surface-400'
                              : ''
                          "
                        >
                          {{
                            slot.changeType === 'CANCEL'
                              ? $t('timetable.cancelled')
                              : slot.subjectName
                          }}
                        </p>
                        <p v-if="slot.isChanged" class="text-xs text-orange-500">
                          <i class="pi pi-exclamation-circle" />
                          {{
                            slot.changeType === 'ADD'
                              ? $t('timetable.added')
                              : $t('timetable.changed')
                          }}
                        </p>
                        <p v-if="slot.teacherName" class="text-xs text-surface-500">
                          {{ slot.teacherName }}
                        </p>
                        <p v-if="slot.roomName" class="text-xs text-surface-400">
                          {{ slot.roomName }}
                        </p>
                      </div>
                    </template>
                  </template>
                </td>
              </tr>
            </template>
            <!-- 時限定義なし（全スロットをまとめて表示） -->
            <template v-else>
              <tr>
                <td
                  class="border border-surface-300 bg-surface-50 p-2 text-center text-sm text-surface-400 dark:border-surface-600 dark:bg-surface-800"
                >
                  —
                </td>
                <td
                  v-for="key in dayKeys"
                  :key="key"
                  class="border border-surface-300 p-1 align-top dark:border-surface-600"
                  :class="{ 'bg-surface-100 dark:bg-surface-800': weeklyView.days[key]?.isDayOff }"
                >
                  <div v-if="weeklyView.days[key]?.isDayOff" class="text-xs text-red-500">
                    {{ weeklyView.days[key].dayOffReason ?? $t('timetable.day_off') }}
                  </div>
                  <div
                    v-for="slot in weeklyView.days[key]?.slots ?? []"
                    :key="slot.periodNumber"
                    class="mb-1 rounded p-1"
                    :style="slot.color ? { backgroundColor: slot.color + '20' } : {}"
                  >
                    <p class="text-xs font-semibold text-surface-400">
                      {{ slot.periodNumber }}{{ $t('timetable.period_suffix') }}
                    </p>
                    <p
                      class="font-medium"
                      :class="
                        slot.changeType === 'CANCEL' ? 'line-through text-surface-400' : ''
                      "
                    >
                      {{ slot.subjectName }}
                    </p>
                    <p v-if="slot.teacherName" class="text-xs text-surface-500">
                      {{ slot.teacherName }}
                    </p>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <!-- 管理者向けアクション -->
      <div v-if="canManage && selectedTimetable?.status === 'ACTIVE'" class="mt-4">
        <Button
          :label="$t('timetable.add_change')"
          icon="pi pi-plus"
          severity="warn"
          size="small"
          @click="emit('open-change-dialog')"
        />
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-table"
      :message="$t('timetable.no_timetable')"
    />
  </div>
</template>
