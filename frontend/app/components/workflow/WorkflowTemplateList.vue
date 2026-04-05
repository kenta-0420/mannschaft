<script setup lang="ts">
import type { WorkflowTemplateResponse } from '~/types/workflow'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  canEdit: boolean
}>()

const workflowApi = useWorkflowApi()
const notification = useNotification()

const templates = ref<WorkflowTemplateResponse[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)

// 作成ダイアログ
const showCreateDialog = ref(false)
const editTemplate = ref<WorkflowTemplateResponse | null>(null)
const showEditDialog = ref(false)
const submitting = ref(false)

const form = ref({
  name: '',
  description: '',
  isSealRequired: false,
})

async function loadTemplates() {
  loading.value = true
  try {
    const res = await workflowApi.listTemplates(props.scopeType, props.scopeId, {
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

async function onDelete(templateId: number) {
  if (!confirm('このテンプレートを削除しますか？')) return
  try {
    await workflowApi.deleteTemplate(props.scopeType, props.scopeId, templateId)
    notification.success('テンプレートを削除しました')
    await loadTemplates()
  } catch {
    notification.error('削除に失敗しました')
  }
}

async function onToggleActive(template: WorkflowTemplateResponse) {
  try {
    if (template.isActive) {
      await workflowApi.deactivateTemplate(props.scopeType, props.scopeId, template.id)
      notification.success('テンプレートを無効化しました')
    } else {
      await workflowApi.activateTemplate(props.scopeType, props.scopeId, template.id)
      notification.success('テンプレートを有効化しました')
    }
    await loadTemplates()
  } catch {
    notification.error('操作に失敗しました')
  }
}

function openEdit(template: WorkflowTemplateResponse) {
  editTemplate.value = template
  form.value = {
    name: template.name,
    description: template.description ?? '',
    isSealRequired: template.isSealRequired,
  }
  showEditDialog.value = true
}

function openCreate() {
  form.value = { name: '', description: '', isSealRequired: false }
  showCreateDialog.value = true
}

async function submitCreate() {
  if (!form.value.name.trim()) return
  submitting.value = true
  try {
    await workflowApi.createTemplate(props.scopeType, props.scopeId, {
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      isSealRequired: form.value.isSealRequired,
    })
    notification.success('テンプレートを作成しました')
    showCreateDialog.value = false
    await loadTemplates()
  } catch {
    notification.error('作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

async function submitEdit() {
  if (!editTemplate.value || !form.value.name.trim()) return
  submitting.value = true
  try {
    await workflowApi.updateTemplate(props.scopeType, props.scopeId, editTemplate.value.id, {
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      isSealRequired: form.value.isSealRequired,
      version: editTemplate.value.version,
    })
    notification.success('テンプレートを更新しました')
    showEditDialog.value = false
    await loadTemplates()
  } catch {
    notification.error('更新に失敗しました')
  } finally {
    submitting.value = false
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadTemplates()
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

onMounted(loadTemplates)

defineExpose({ refresh: loadTemplates })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-bold">ワークフローテンプレート</h2>
      <Button v-if="canEdit" label="テンプレート作成" icon="pi pi-plus" @click="openCreate" />
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
      <Column header="テンプレート名" field="name" style="min-width: 200px" />
      <Column header="説明" field="description" style="min-width: 200px">
        <template #body="{ data }">
          {{ data.description || '—' }}
        </template>
      </Column>
      <Column header="有効" style="width: 80px">
        <template #body="{ data }">
          <Tag
            :value="data.isActive ? '有効' : '無効'"
            :severity="data.isActive ? 'success' : 'secondary'"
          />
        </template>
      </Column>
      <Column header="押印" style="width: 80px">
        <template #body="{ data }">
          <i
            :class="
              data.isSealRequired ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-400'
            "
          />
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
            <Button icon="pi pi-pencil" text rounded size="small" @click="openEdit(data)" />
            <Button
              :icon="data.isActive ? 'pi pi-pause' : 'pi pi-play'"
              text
              rounded
              size="small"
              :severity="data.isActive ? 'warn' : 'success'"
              @click="onToggleActive(data)"
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
        <DashboardEmptyState icon="pi pi-sitemap" message="テンプレートはありません" />
      </template>
    </DataTable>

    <!-- 作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="テンプレート作成"
      :style="{ width: '500px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium"
            >テンプレート名 <span class="text-red-500">*</span></label
          >
          <InputText v-model="form.name" class="w-full" placeholder="テンプレート名" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="form.description" rows="3" class="w-full" placeholder="説明（任意）" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isSealRequired" :binary="true" input-id="sealReq" />
          <label for="sealReq" class="text-sm">押印を必須にする</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showCreateDialog = false" />
        <Button label="作成" icon="pi pi-check" :loading="submitting" @click="submitCreate" />
      </template>
    </Dialog>

    <!-- 編集ダイアログ -->
    <Dialog
      v-model:visible="showEditDialog"
      header="テンプレート編集"
      :style="{ width: '500px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium"
            >テンプレート名 <span class="text-red-500">*</span></label
          >
          <InputText v-model="form.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="form.description" rows="3" class="w-full" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isSealRequired" :binary="true" input-id="sealReqEdit" />
          <label for="sealReqEdit" class="text-sm">押印を必須にする</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showEditDialog = false" />
        <Button label="更新" icon="pi pi-check" :loading="submitting" @click="submitEdit" />
      </template>
    </Dialog>
  </div>
</template>
