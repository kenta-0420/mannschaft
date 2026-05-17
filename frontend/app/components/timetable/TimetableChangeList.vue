<script setup lang="ts">
/**
 * 時間割 — 臨時変更タブ
 *
 * 親 (teams/[id]/timetable.vue) から渡された臨時変更一覧を DataTable で表示する。
 * 追加・削除操作は emits で親に伝える。API 呼び出しは親に集約。
 */
import type { Timetable, TimetableChange } from '~/types/timetable'

defineProps<{
  selectedTimetable: Timetable | null
  changes: TimetableChange[]
  canManage: boolean
}>()

const emit = defineEmits<{
  (e: 'open-change-dialog'): void
  (e: 'delete-change', id: number): void
}>()
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <p class="text-sm text-surface-500">
        {{ selectedTimetable?.name }}
      </p>
      <Button
        v-if="canManage && selectedTimetable?.status === 'ACTIVE'"
        :label="$t('timetable.add_change')"
        icon="pi pi-plus"
        size="small"
        @click="emit('open-change-dialog')"
      />
    </div>
    <DataTable :value="changes" data-key="id" striped-rows>
      <template #empty>
        <div class="py-8 text-center text-surface-500">{{ $t('timetable.no_change') }}</div>
      </template>
      <Column field="targetDate" :header="$t('timetable.change_date')" />
      <Column :header="$t('timetable.period_number')">
        <template #body="{ data }">
          {{ data.periodNumber != null ? data.periodNumber + $t('timetable.period_suffix') : $t('timetable.period_all') }}
        </template>
      </Column>
      <Column :header="$t('timetable.change_type')">
        <template #body="{ data }">
          <Badge
            :value="
              ({
                REPLACE: $t('timetable.change_type_replace'),
                CANCEL: $t('timetable.change_type_cancel'),
                ADD: $t('timetable.change_type_add'),
                DAY_OFF: $t('timetable.change_type_day_off'),
              } as Record<string, string>)[data.changeType] ?? data.changeType
            "
            :severity="
              ({
                REPLACE: 'warn',
                CANCEL: 'secondary',
                ADD: 'success',
                DAY_OFF: 'danger',
              } as Record<string, string>)[data.changeType] ?? 'info'
            "
          />
        </template>
      </Column>
      <Column field="subjectName" :header="$t('timetable.change_subject')" />
      <Column field="reason" :header="$t('timetable.change_reason')" />
      <Column v-if="canManage" :header="$t('common.label.actions')" style="width: 80px">
        <template #body="{ data }">
          <Button
            icon="pi pi-trash"
            size="small"
            text
            severity="danger"
            @click="emit('delete-change', data.id)"
          />
        </template>
      </Column>
    </DataTable>
  </div>
</template>
