<script setup lang="ts">
import type { ActivityTemplate } from '~/types/activity'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const { getTemplates, deleteTemplate, duplicateTemplate } = useActivityApi()
const { showError, showSuccess } = useNotification()

const templates = ref<ActivityTemplate[]>([])
const loading = ref(false)
const showForm = ref(false)
const editingTemplate = ref<ActivityTemplate | null>(null)

async function loadTemplates() {
  loading.value = true
  try {
    const res = await getTemplates('TEAM', teamId.value)
    templates.value = res.data
  } catch {
    showError('テンプレートの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(loadTemplates)

function openCreate() {
  editingTemplate.value = null
  showForm.value = true
}

function openEdit(t: ActivityTemplate) {
  editingTemplate.value = t
  showForm.value = true
}

async function handleDelete(id: number) {
  if (!confirm('このテンプレートを削除しますか？')) return
  try {
    await deleteTemplate(id)
    showSuccess('テンプレートを削除しました')
    await loadTemplates()
  } catch {
    showError('テンプレートの削除に失敗しました')
  }
}

async function handleDuplicate(id: number) {
  try {
    await duplicateTemplate(id)
    showSuccess('テンプレートを複製しました')
    await loadTemplates()
  } catch {
    showError('テンプレートの複製に失敗しました')
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}
</script>

<template>
  <div class="p-4">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-xl font-bold">活動テンプレート管理</h1>
      <Button label="テンプレートを作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <DataTable
      v-else
      :value="templates"
      striped-rows
      class="text-sm"
    >
      <template #empty>
        <div class="py-12 text-center text-surface-400">
          <i class="pi pi-file mb-3 text-4xl text-surface-300" />
          <p>テンプレートがありません</p>
        </div>
      </template>

      <Column field="name" header="テンプレート名" />

      <Column header="フィールド数">
        <template #body="{ data }">
          {{ data.fields?.length ?? 0 }} 件
        </template>
      </Column>

      <Column header="作成日">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>

      <Column header="操作" style="width: 160px">
        <template #body="{ data }">
          <div class="flex items-center gap-1">
            <Button
              v-tooltip.top="'編集'"
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              @click="openEdit(data)"
            />
            <Button
              v-tooltip.top="'複製'"
              icon="pi pi-copy"
              text
              rounded
              size="small"
              @click="handleDuplicate(data.id)"
            />
            <Button
              v-tooltip.top="'削除'"
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="handleDelete(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <ActivityTemplateForm
      v-model:visible="showForm"
      :template="editingTemplate"
      @saved="loadTemplates"
    />
  </div>
</template>
