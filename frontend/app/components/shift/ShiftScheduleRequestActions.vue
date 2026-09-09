<script setup lang="ts">
import type { ShiftScheduleResponse } from '~/types/shift'

/**
 * シフト表ごとの導線ボタン（CMP-260908-2118）。
 *
 * `ShiftScheduleList` は行クリックで「1日1件の希望ダイアログ」を開くのみで、
 * 一括入力ページ（/my/shift-request）とシフトボードへの導線が無かった。
 * ここでシフト表ごとに以下を出す。
 * - まとめて希望を出す（全メンバー。ただし希望受付中のシフト表のみ）
 * - シフトボード（ADMIN / DEPUTY_ADMIN のみ。受付期限とは無関係に出す）
 *
 * 受付可否の判定（status / requestDeadline）は親ページが行い、
 * 受付中のシフト表 ID の集合として受け取る（判定の二重化を避けるため）。
 */
const props = defineProps<{
  teamSlug: string
  schedules: ShiftScheduleResponse[]
  /** 希望を受け付けているシフト表の ID 一覧（親ページが status / requestDeadline から算出） */
  acceptingScheduleIds: number[]
  /** シフトボードを出してよいか（ADMIN / DEPUTY_ADMIN） */
  canManage: boolean
}>()

const { t } = useI18n()

function isAccepting(scheduleId: number): boolean {
  return props.acceptingScheduleIds.includes(scheduleId)
}

function boardPath(scheduleId: number): string {
  return `/teams/${props.teamSlug}/shifts/${scheduleId}/board`
}
</script>

<template>
  <div v-if="schedules.length > 0" class="mt-6">
    <h3 class="mb-2 text-sm font-semibold text-surface-700">
      {{ t('shift.entry.sectionTitle') }}
    </h3>
    <div class="space-y-2">
      <div
        v-for="s in schedules"
        :key="s.id"
        class="rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <p class="mb-2 text-sm font-medium">{{ s.content.title }}</p>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            v-if="isAccepting(s.id)"
            :label="t('shift.entry.bulkRequest')"
            icon="pi pi-list-check"
            size="small"
            outlined
            @click="navigateTo('/my/shift-request')"
          />
          <span v-else class="text-xs text-surface-500">
            {{ t('shift.entry.closed') }}
          </span>
          <Button
            v-if="canManage"
            :label="t('shift.entry.board')"
            icon="pi pi-th-large"
            size="small"
            severity="secondary"
            outlined
            @click="navigateTo(boardPath(s.id))"
          />
        </div>
      </div>
    </div>
  </div>
</template>
