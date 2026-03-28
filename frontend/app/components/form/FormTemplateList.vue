<script setup lang="ts">
import type { FormTemplateResponse } from '~/types/form'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  canEdit: boolean
}>()

const formApi = useFormApi()
const notification = useNotification()

const templates = ref<FormTemplateResponse[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)

const statusFilter = ref('')

const statusOptions = [
  { label: '全て', value: '' },
  { label: '下書き', value: 'DRAFT' },
  { label: '公開中', value: 'PUBLISHED' },
  { label: '終了', value: 'CLOSED' },
]

function statusSeverity(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'PUBLISHED':
      return 'success'
    case 'CLOSED':
      return 'warn'
    default:
      return 'info'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'PUBLISHED':
      return '公開中'
    case 'CLOSED':
      return '終了'
    default:
      return status
  }
}

async function loadTemplates() {
  loading.value = true
  try {
    const res = await formApi.listTemplates(props.scopeType, props.scopeId, {
      status: statusFilter.value || undefined,
      page: page.value,
      size: rows.value,
    })
    templates.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    templates.value = []
  } finally {
    loading.value = false
  }
}

async function onPublish(templateId: number) {
  try {
    await formApi.publishTemplate(props.scopeType, props.scopeId, templateId)
    notification.success('フォームを公開しました')
    await loadTemplates()
  } catch {
    notification.error('公開に失敗しました')
  }
}

async function onClose(templateId: number) {
  try {
    await formApi.closeTemplate(props.scopeType, props.scopeId, templateId)
    notification.success('フォームを終了しました')
    await loadTemplates()
  } catch {
    notification.error('終了に失敗しました')
  }
}

async function onDelete(templateId: number) {
  if (!confirm('このテンプレートを削除しますか？')) return
  try {
    await formApi.deleteTemplate(props.scopeType, props.scopeId, templateId)
    notification.success('テンプレートを削除しました')
    await loadTemplates()
  } catch {
    notification.error('削除に失敗しました')
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadTemplates()
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

watch(statusFilter, () => {
  page.value = 0
  loadTemplates()
})

onMounted(loadTemplates)

defineExpose({ refresh: loadTemplates })
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
      :value="templates"
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
      <Column header="フォーム名" field="name" style="min-width: 200px" />
      <Column header="説明" field="description" style="min-width: 200px">
        <template #body="{ data }">
          {{ data.description || '—' }}
        </template>
      </Column>
      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="提出数" style="width: 120px">
        <template #body="{ data }">
          {{ data.submissionCount
          }}<span v-if="data.targetCount" class="text-surface-400"> / {{ data.targetCount }}</span>
        </template>
      </Column>
      <Column header="期限" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.deadline) }}
        </template>
      </Column>
      <Column header="作成日" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>
      <Column v-if="canEdit" header="操作" style="width: 160px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="data.status === 'DRAFT'"
              icon="pi pi-send"
              text
              rounded
              size="small"
              severity="success"
              title="公開"
              @click="onPublish(data.id)"
            />
            <Button
              v-if="data.status === 'PUBLISHED'"
              icon="pi pi-lock"
              text
              rounded
              size="small"
              severity="warn"
              title="終了"
              @click="onClose(data.id)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="onDelete(data.id)"
            />
          </div>
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-file-edit" message="フォームテンプレートはありません" />
      </template>
    </DataTable>
  </div>
</template>
