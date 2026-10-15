<script setup lang="ts">
/**
 * 時間割 — 時間割一覧タブ
 *
 * 親 (teams/[id]/timetable.vue) から渡された時間割一覧を DataTable で表示する。
 * 行クリック・アクション操作は emits で親に伝える。API 呼び出しは親に集約。
 */
import type { Timetable, TimetableStatus } from '~/types/timetable'

defineProps<{
  timetables: Timetable[]
  canManage: boolean
  statusLabel: (s: TimetableStatus | string) => string
  statusSeverity: (s: TimetableStatus | string) => 'warn' | 'success' | 'secondary' | 'info'
}>()

const emit = defineEmits<{
  (e: 'select', timetable: Timetable): void
  (e: 'activate' | 'archive' | 'revert-to-draft' | 'duplicate', id: number): void
}>()
</script>

<template>
  <DataTable
    :value="timetables"
    data-key="id"
    striped-rows
    @row-click="(e: { data: Timetable }) => emit('select', e.data)"
  >
    <template #empty>
      <div class="py-8 text-center text-surface-500">
        {{ $t('timetable.no_timetable') }}
      </div>
    </template>
    <Column field="name" :header="$t('timetable.timetable_name')" />
    <Column field="termName" :header="$t('timetable.term')" />
    <Column field="effectiveFrom" :header="$t('timetable.effective_from')" />
    <Column :header="$t('common.label.status')">
      <template #body="{ data }">
        <Badge
          :value="statusLabel(data.status)"
          :severity="statusSeverity(data.status)"
        />
      </template>
    </Column>
    <Column v-if="canManage" :header="$t('common.label.actions')" style="width: 200px">
      <template #body="{ data }">
        <div class="flex gap-1">
          <Button
            v-if="data.status === 'DRAFT'"
            v-tooltip="$t('timetable.activate')"
            icon="pi pi-check"
            size="small"
            text
            severity="success"
            @click.stop="emit('activate', data.id)"
          />
          <Button
            v-if="data.status === 'ACTIVE'"
            v-tooltip="$t('timetable.archive')"
            icon="pi pi-inbox"
            size="small"
            text
            severity="secondary"
            @click.stop="emit('archive', data.id)"
          />
          <Button
            v-if="data.status === 'ARCHIVED'"
            v-tooltip="$t('timetable.revert_draft')"
            icon="pi pi-undo"
            size="small"
            text
            severity="warn"
            @click.stop="emit('revert-to-draft', data.id)"
          />
          <Button
            v-tooltip="$t('timetable.duplicate')"
            icon="pi pi-copy"
            size="small"
            text
            severity="info"
            @click.stop="emit('duplicate', data.id)"
          />
        </div>
      </template>
    </Column>
  </DataTable>
</template>
