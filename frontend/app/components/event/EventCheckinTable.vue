<script setup lang="ts">
import type { CheckinResponse } from '~/types/event'
import { formatDateTime } from '~/utils/eventFormat'

defineProps<{
  checkins: CheckinResponse[]
}>()
</script>

<template>
  <DataTable :value="checkins" data-key="id" row-hover>
    <Column header="ID" field="id" style="width: 80px" />
    <Column header="種別" field="checkinType" style="width: 120px" />
    <Column header="チェックイン日時" style="width: 200px">
      <template #body="{ data }">
        {{ formatDateTime(data.checkedInAt) }}
      </template>
    </Column>
    <Column header="メモ" field="note" style="min-width: 150px">
      <template #body="{ data }">
        {{ data.note || '—' }}
      </template>
    </Column>
    <template #empty>
      <DashboardEmptyState icon="pi pi-sign-in" message="チェックインはありません" />
    </template>
  </DataTable>
</template>
