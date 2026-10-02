<script setup lang="ts">
/**
 * F03.17 キープ一覧の1件カード。
 *
 * - status ごとに編集可否を事前に無効化する（§4.4「押してから知る」を避ける・段階開示）。
 * - 候補日バッジは1タップで即変換（§4.5.3）。
 * - candidateDates は `YYYY-MM-DD` 文字列のまま表示する（Date へ変換して TZ 投影し直すと
 *   1日ずれる・localDate.ts の原則。バッジ表示はテキストのまま分解するだけで Date を経由しない）。
 */
import type { ScheduleKeepResponse } from '~/composables/schedule/useScheduleKeep'

const props = defineProps<{
  keep: ScheduleKeepResponse
  canEdit: boolean
  reorderMode: boolean
}>()

const emit = defineEmits<{
  convertCandidate: [date: string]
  convertNoDate: []
  archive: []
  restore: []
  revert: []
  remove: []
  edit: []
}>()

const { t } = useI18n()

const statusKey = computed(() => {
  switch (props.keep.status) {
    case 'SCHEDULED': return 'scheduleKeep.status.scheduled'
    case 'ARCHIVED': return 'scheduleKeep.status.archived'
    default: return 'scheduleKeep.status.kept'
  }
})

const lockedHintKey = computed(() => {
  if (props.keep.status === 'SCHEDULED') return 'scheduleKeep.lockedHint.scheduled'
  if (props.keep.status === 'ARCHIVED') return 'scheduleKeep.lockedHint.archived'
  return null
})

const originHintKey = computed(() => {
  if (props.keep.convertedScheduleState === 'CANCELLED') return 'scheduleKeep.origin.cancelled'
  if (props.keep.convertedScheduleState === 'DELETED') return 'scheduleKeep.origin.deleted'
  return null
})

function formatCandidateDate(d: string): string {
  // YYYY-MM-DD をそのまま分解表示する（Date を経由しない = TZ ずれの余地を作らない）。
  const [y, m, day] = d.split('-')
  return `${y}/${m}/${day}`
}
</script>

<template>
  <div
    class="rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900"
    data-testid="schedule-keep-card"
  >
    <div class="flex items-start gap-3">
      <i
        v-if="reorderMode"
        class="drag-handle pi pi-bars mt-1 cursor-move text-surface-400"
        aria-hidden="true"
      />
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-2">
          <h3 class="truncate font-semibold text-surface-900 dark:text-surface-50">
            {{ keep.title }}
          </h3>
          <Tag :value="t(statusKey)" severity="secondary" />
        </div>

        <p
          v-if="keep.memo"
          class="mt-1 line-clamp-2 text-sm text-surface-600 dark:text-surface-300"
        >
          {{ keep.memo }}
        </p>

        <p v-if="originHintKey" class="mt-1 text-sm text-orange-600 dark:text-orange-400">
          {{ t(originHintKey) }}
        </p>

        <p v-if="lockedHintKey" class="mt-1 text-xs text-surface-400 dark:text-surface-500">
          {{ t(lockedHintKey) }}
        </p>

        <!-- 候補日バッジ: 1タップで変換（KEPT のみ） -->
        <div
          v-if="keep.status === 'KEPT' && keep.candidateDates && keep.candidateDates.length > 0"
          class="mt-2 flex flex-wrap gap-2"
        >
          <Button
            v-for="date in keep.candidateDates"
            :key="date"
            :label="formatCandidateDate(date)"
            size="small"
            outlined
            icon="pi pi-calendar"
            data-testid="schedule-keep-candidate-badge"
            @click="emit('convertCandidate', date)"
          />
        </div>

        <!-- アクション -->
        <div class="mt-3 flex flex-wrap gap-2">
          <Button
            v-if="keep.status === 'KEPT'"
            :label="t('scheduleKeep.action.convert')"
            size="small"
            icon="pi pi-calendar-plus"
            data-testid="schedule-keep-convert-button"
            @click="emit('convertNoDate')"
          />
          <Button
            v-if="canEdit && keep.status === 'KEPT'"
            :label="t('button.edit')"
            size="small"
            text
            icon="pi pi-pencil"
            @click="emit('edit')"
          />
          <Button
            v-if="canEdit && keep.status !== 'ARCHIVED'"
            :label="t('scheduleKeep.action.archive')"
            size="small"
            text
            icon="pi pi-inbox"
            @click="emit('archive')"
          />
          <Button
            v-if="canEdit && keep.status === 'ARCHIVED'"
            :label="t('scheduleKeep.action.restore')"
            size="small"
            text
            icon="pi pi-replay"
            @click="emit('restore')"
          />
          <Button
            v-if="canEdit && keep.convertedScheduleId"
            :label="t('scheduleKeep.action.revert')"
            size="small"
            text
            severity="warn"
            icon="pi pi-undo"
            @click="emit('revert')"
          />
          <Button
            v-if="canEdit"
            :label="t('button.delete')"
            size="small"
            text
            severity="danger"
            icon="pi pi-trash"
            @click="emit('remove')"
          />
        </div>
      </div>
    </div>
  </div>
</template>
