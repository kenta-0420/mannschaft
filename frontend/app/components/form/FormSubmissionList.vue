<script setup lang="ts">
import type { FormSubmissionResponse } from '~/types/form'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  templateId?: number
  myOnly?: boolean
  canManage?: boolean
}>()

const formApi = useFormApi()
const notification = useNotification()

const submissions = ref<FormSubmissionResponse[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)

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
    case 'RETURNED':
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
      return '提出済'
    case 'APPROVED':
      return '承認済'
    case 'REJECTED':
      return '却下'
    case 'RETURNED':
      return '差戻し'
    default:
      return status
  }
}

async function loadSubmissions() {
  loading.value = true
  try {
    if (props.myOnly) {
      const res = await formApi.listMySubmissions(props.scopeType, props.scopeId, {
        page: page.value,
        size: rows.value,
      })
      submissions.value = res.data
      totalRecords.value = res.meta.totalElements
    } else if (props.templateId) {
      const res = await formApi.listTemplateSubmissions(
        props.scopeType,
        props.scopeId,
        props.templateId,
        { page: page.value, size: rows.value },
      )
      submissions.value = res.data
      totalRecords.value = res.meta.totalElements
    }
  } catch {
    submissions.value = []
  } finally {
    loading.value = false
  }
}

async function onApprove(submissionId: number) {
  if (!props.templateId) return
  try {
    await formApi.approveSubmission(props.scopeType, props.scopeId, props.templateId, submissionId)
    notification.success('提出を承認しました')
    await loadSubmissions()
  } catch {
    notification.error('承認に失敗しました')
  }
}

async function onReject(submissionId: number) {
  if (!props.templateId) return
  try {
    await formApi.rejectSubmission(props.scopeType, props.scopeId, props.templateId, submissionId)
    notification.success('提出を却下しました')
    await loadSubmissions()
  } catch {
    notification.error('却下に失敗しました')
  }
}

async function onReturn(submissionId: number) {
  if (!props.templateId) return
  try {
    await formApi.returnSubmission(props.scopeType, props.scopeId, props.templateId, submissionId)
    notification.success('提出を差し戻しました')
    await loadSubmissions()
  } catch {
    notification.error('差戻しに失敗しました')
  }
}

async function onDelete(submissionId: number) {
  if (!confirm('この提出を削除しますか？')) return
  try {
    await formApi.deleteSubmission(props.scopeType, props.scopeId, submissionId)
    notification.success('提出を削除しました')
    await loadSubmissions()
  } catch {
    notification.error('削除に失敗しました')
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadSubmissions()
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

onMounted(loadSubmissions)

defineExpose({ refresh: loadSubmissions })
</script>

<template>
  <div>
    <DataTable
      :value="submissions"
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
      <Column header="ID" field="id" style="width: 80px" />
      <Column header="提出者" style="width: 120px">
        <template #body="{ data }"> ユーザー #{{ data.submittedBy }} </template>
      </Column>
      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="提出回数" field="submissionCountForUser" style="width: 100px" />
      <Column header="作成日" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>
      <Column header="更新日" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.updatedAt) }}
        </template>
      </Column>
      <Column v-if="canManage" header="操作" style="width: 160px">
        <template #body="{ data }">
          <div v-if="data.status === 'SUBMITTED'" class="flex gap-1">
            <Button
              icon="pi pi-check"
              text
              rounded
              size="small"
              severity="success"
              title="承認"
              @click="onApprove(data.id)"
            />
            <Button
              icon="pi pi-times"
              text
              rounded
              size="small"
              severity="danger"
              title="却下"
              @click="onReject(data.id)"
            />
            <Button
              icon="pi pi-replay"
              text
              rounded
              size="small"
              severity="warn"
              title="差戻し"
              @click="onReturn(data.id)"
            />
          </div>
          <Button
            v-if="data.status === 'DRAFT'"
            icon="pi pi-trash"
            text
            rounded
            size="small"
            severity="danger"
            @click="onDelete(data.id)"
          />
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-inbox" message="提出はありません" />
      </template>
    </DataTable>
  </div>
</template>
