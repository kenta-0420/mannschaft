<script setup lang="ts">
import type { SystemTemplateResponse, CreateTemplateRequest } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const items = ref<SystemTemplateResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const form = ref<CreateTemplateRequest>({ name: '', templateJson: '' })

async function load() {
  loading.value = true
  try {
    // Templates don't have a GET list in system-admin, they come from modules or other scope
    // Using available endpoints
    items.value = []
  } catch {
    showError('テンプレートの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', templateJson: '{}' }
  showDialog.value = true
}

async function save() {
  try {
    await systemAdminApi.createTemplate(form.value)
    success('テンプレートを作成しました')
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  }
}

async function remove(id: number) {
  try {
    await systemAdminApi.deleteTemplate(id)
    success('テンプレートを削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">テンプレート管理</h1>
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="items" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">テンプレートがありません</div>
      </template>
      <Column field="name" header="テンプレート名" />
      <Column field="titleTemplate" header="タイトルテンプレート" />
      <Column field="scope" header="スコープ" style="width: 100px" />
      <Column header="デフォルト時間" style="width: 120px">
        <template #body="{ data }"> {{ data.defaultDurationMinutes }}分 </template>
      </Column>
      <Column field="sortOrder" header="表示順" style="width: 80px" />
      <Column header="フィールド" style="width: 100px">
        <template #body="{ data }">
          <Badge :value="String(data.customFieldValues?.length ?? 0)" severity="info" />
        </template>
      </Column>
      <Column header="操作" style="width: 120px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              @click="remove(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      header="テンプレート作成"
      :style="{ width: '600px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">テンプレート名</label>
          <InputText v-model="form.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">テンプレートJSON</label>
          <Textarea v-model="form.templateJson" rows="10" class="w-full font-mono text-sm" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
          <Button label="作成" @click="save" />
        </div>
      </template>
    </Dialog>
  </div>
</template>
