<script setup lang="ts">
import type { WorkflowRequestResponse } from '~/types/workflow'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const emit = defineEmits<{
  select: [requestId: number]
}>()

const workflowApi = useWorkflowApi()

const requests = ref<WorkflowRequestResponse[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)

const statusFilter = ref('')

const statusOptions = [
  { label: '全て', value: '' },
  { label: '下書き', value: 'DRAFT' },
  { label: '申請中', value: 'SUBMITTED' },
  { label: '承認済', value: 'APPROVED' },
  { label: '却下', value: 'REJECTED' },
  { label: '取下げ', value: 'WITHDRAWN' },
]

function statusSeverity(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'SUBMITTED':
      return 'info'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'warn'
    default:
      return 'info'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'SUBMITTED':
      return '申請中'
    case 'APPROVED':
      return '承認済'
    case 'REJECTED':
      return '却下'
    case 'WITHDRAWN':
      return '取下げ'
    default:
      return status
  }
}

async function loadRequests() {
  loading.value = true
  try {
    const res = await workflowApi.listRequests(props.scopeType, props.scopeId, {
      status: statusFilter.value || undefined,
      page: page.value,
      size: rows.value,
    })
    requests.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    requests.value = []
  } finally {
    loading.value = false
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadRequests()
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

watch(statusFilter, () => {
  page.value = 0
  loadRequests()
})

onMounted(loadRequests)

defineExpose({ refresh: loadRequests })
</script>

<template>
  <div>
    <!-- フィルター -->
    <div class="mb-4 flex flex-wrap items-end gap-3">
      <div class="w-40">
        <label class="mb-1 block text-xs font-medium">ステータス</label>
        <Select
          v-model="statusFilter"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
    </div>

    <DataTable
      :value="requests"
      :loading="loading"
      lazy
      paginator
      :rows="rows"
      :total-records="totalRecords"
      :rows-per-page-options="[10, 20, 50]"
      data-key="id"
      row-hover
      @page="onPage"
    >
      <Column header="タイトル" field="title" style="min-width: 200px">
        <template #body="{ data }">
          <NuxtLink
            :to="`/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/workflows/${data.id}`"
            class="font-medium hover:text-primary"
            @click.prevent="emit('select', data.id)"
          >
            {{ data.title || `申請 #${data.id}` }}
          </NuxtLink>
        </template>
      </Column>
      <Column header="ステータス" field="status" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="ステップ" style="width: 100px">
        <template #body="{ data }">
          {{ data.currentStepOrder ?? '—' }}
        </template>
      </Column>
      <Column header="申請日" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.requestedAt) }}
        </template>
      </Column>
      <Column header="作成日" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>
      <Column header="操作" style="width: 80px">
        <template #body="{ data }">
          <Button icon="pi pi-eye" text rounded size="small" @click="emit('select', data.id)" />
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-file" message="申請はありません" />
      </template>
    </DataTable>
  </div>
</template>
