<script setup lang="ts">
import type { TimetableItemResponse } from '~/types/event'
import { formatDateTime } from '~/utils/eventFormat'

defineProps<{
  timetableItems: TimetableItemResponse[]
}>()
</script>

<template>
  <DataTable :value="timetableItems" data-key="id" row-hover>
    <Column header="タイトル" field="title" style="min-width: 200px" />
    <Column header="登壇者" field="speaker" style="width: 150px">
      <template #body="{ data }">
        {{ data.speaker || '—' }}
      </template>
    </Column>
    <Column header="開始" style="width: 160px">
      <template #body="{ data }">
        {{ formatDateTime(data.startAt) }}
      </template>
    </Column>
    <Column header="終了" style="width: 160px">
      <template #body="{ data }">
        {{ formatDateTime(data.endAt) }}
      </template>
    </Column>
    <Column header="場所" field="location" style="width: 150px">
      <template #body="{ data }">
        {{ data.location || '—' }}
      </template>
    </Column>
    <template #empty>
      <DashboardEmptyState icon="pi pi-clock" message="タイムテーブルはありません" />
    </template>
  </DataTable>
</template>
