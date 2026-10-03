<script setup lang="ts">
/**
 * 寄合詳細 — 出欠セクション（F17.2 Wave1 ②寄合後半戦 §4.4）。
 *
 * - CONFIRMED のみ回答ボタンを表示（PLANNING/CANCELLED は書込み不可・§4.5）
 * - 自分の回答はハイライトし、再タップで変更できる（upsert）
 * - 回答者一覧のみ表示する。未回答者一覧は絶対に出さない（G1）
 * - 表示は村ニックネーム（`displayName`）のみ。実名は出さない（G4）
 *
 * ロジックは持たない（API 呼び出しは親が担う）。
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import type {
  VillageMeetupAttendanceResponse,
  VillageMeetupAttendanceStatus,
} from '~/types/village'

defineProps<{
  attendances: VillageMeetupAttendanceResponse[]
  myStatus: VillageMeetupAttendanceStatus | null
  /** 自分の出欠を回答できるか（CONFIRMED のみ） */
  canRespond: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  respond: [status: VillageMeetupAttendanceStatus]
}>()

const { t } = useI18n()

const statusOptions: VillageMeetupAttendanceStatus[] = ['GOING', 'MAYBE', 'ABSENT']

function severityFor(status: VillageMeetupAttendanceStatus): 'success' | 'warn' | 'danger' {
  switch (status) {
    case 'GOING':
      return 'success'
    case 'MAYBE':
      return 'warn'
    case 'ABSENT':
      return 'danger'
  }
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <h3 class="font-semibold">
      {{ t('village.meetup.attendance.title') }}
    </h3>

    <div v-if="canRespond" class="flex items-center gap-2 flex-wrap">
      <Button
        v-for="status in statusOptions"
        :key="status"
        :label="t(`village.meetup.attendance.${status.toLowerCase()}`)"
        size="small"
        :severity="severityFor(status)"
        :outlined="myStatus !== status"
        @click="emit('respond', status)"
      />
    </div>
    <p v-else class="text-xs text-surface-500">
      {{ t('village.meetup.attendance.notConfirmedHint') }}
    </p>

    <div v-if="loading" class="text-center py-3 text-surface-500">
      <i class="pi pi-spin pi-spinner" />
    </div>
    <div v-else-if="attendances.length === 0" class="text-xs text-surface-500">
      {{ t('village.meetup.attendance.empty') }}
    </div>
    <div v-else class="flex flex-wrap gap-2">
      <div
        v-for="a in attendances"
        :key="a.id"
        class="inline-flex items-center gap-1 rounded-full border border-surface-200 px-2 py-1 text-xs dark:border-surface-700"
      >
        <span>{{ a.displayName ?? t('village.meetup.attendance.unknownMember') }}</span>
        <Badge
          :value="t(`village.meetup.attendance.${a.status.toLowerCase()}`)"
          :severity="severityFor(a.status)"
        />
      </div>
    </div>
  </div>
</template>
