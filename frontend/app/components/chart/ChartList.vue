<script setup lang="ts">
import type { Chart } from '~/types/chart'

defineProps<{
  charts: Chart[]
  loading?: boolean
  totalRecords?: number
}>()

const emit = defineEmits<{
  select: [chart: Chart]
  create: []
  pin: [chartId: number]
  page: [event: { page: number }]
}>()

const statusLabel = (s: string) => s === 'DRAFT' ? '下書き' : '確定'
const statusSeverity = (s: string) => s === 'DRAFT' ? 'warn' : 'success'
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">カルテ一覧</h3>
      <Button label="新規カルテ" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <DataTable
      :value="charts"
      :loading="loading"
      :lazy="true"
      :paginator="!!totalRecords"
      :rows="20"
      :total-records="totalRecords"
      data-key="id"
      striped-rows
      @page="(e: { page: number }) => emit('page', e)"
      @row-click="(e: { data: Chart }) => emit('select', e.data)"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">カルテがありません</div>
      </template>
      <Column header="ピン" style="width: 50px">
        <template #body="{ data }">
          <Button
            :icon="data.isPinned ? 'pi pi-star-fill' : 'pi pi-star'"
            size="small"
            text
            :severity="data.isPinned ? 'warn' : 'secondary'"
            @click.stop="emit('pin', data.id)"
          />
        </template>
      </Column>
      <Column field="clientName" header="顧客名" />
      <Column header="来店日">
        <template #body="{ data }">
          {{ new Date(data.visitDate).toLocaleDateString('ja-JP') }}
        </template>
      </Column>
      <Column field="staffName" header="担当スタッフ" />
      <Column header="ステータス">
        <template #body="{ data }">
          <Badge :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="主訴">
        <template #body="{ data }">
          <span class="line-clamp-1 text-sm">{{ data.chiefComplaint ?? '-' }}</span>
        </template>
      </Column>
    </DataTable>
  </div>
</template>
