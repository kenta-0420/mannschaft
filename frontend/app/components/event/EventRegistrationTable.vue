<script setup lang="ts">
import type { RegistrationResponse } from '~/types/event'
import { formatDateTime, regStatusLabel, regStatusSeverity } from '~/utils/eventFormat'

defineProps<{
  registrations: RegistrationResponse[]
  canEdit: boolean
}>()

const emit = defineEmits<{
  approve: [regId: number]
  reject: [regId: number]
}>()
</script>

<template>
  <DataTable :value="registrations" data-key="id" row-hover>
    <Column header="ID" field="id" style="width: 80px" />
    <Column header="ユーザーID" style="width: 120px">
      <template #body="{ data }">
        {{ data.userId || data.guestName || '—' }}
      </template>
    </Column>
    <Column header="ステータス" style="width: 120px">
      <template #body="{ data }">
        <Tag :value="regStatusLabel(data.status)" :severity="regStatusSeverity(data.status)" />
      </template>
    </Column>
    <Column header="数量" field="quantity" style="width: 80px" />
    <Column header="メモ" field="note" style="min-width: 150px">
      <template #body="{ data }">
        {{ data.note || '—' }}
      </template>
    </Column>
    <Column header="登録日" style="width: 160px">
      <template #body="{ data }">
        {{ formatDateTime(data.createdAt) }}
      </template>
    </Column>
    <Column v-if="canEdit" header="操作" style="width: 120px">
      <template #body="{ data }">
        <div v-if="data.status === 'PENDING'" class="flex gap-1">
          <Button
            icon="pi pi-check"
            text
            rounded
            size="small"
            severity="success"
            @click="emit('approve', data.id)"
          />
          <Button
            icon="pi pi-times"
            text
            rounded
            size="small"
            severity="danger"
            @click="emit('reject', data.id)"
          />
        </div>
      </template>
    </Column>
    <template #empty>
      <DashboardEmptyState icon="pi pi-users" message="参加者はいません" />
    </template>
  </DataTable>
</template>
