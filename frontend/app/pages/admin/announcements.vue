<script setup lang="ts">
import type { AnnouncementResponse, CreateAnnouncementRequest } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const items = ref<AnnouncementResponse[]>([])
const loading = ref(true)
const totalRecords = ref(0)
const page = ref(0)
const showDialog = ref(false)
const editingItem = ref<AnnouncementResponse | null>(null)
const form = ref<CreateAnnouncementRequest>({ title: '', body: '' })

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getAnnouncements({ page: page.value, size: 20 })
    items.value = res.data
    totalRecords.value = res.meta?.totalElements ?? res.data.length
  } catch {
    showError('お知らせ一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { title: '', body: '', priority: 'NORMAL', targetScope: 'ALL', isPinned: false }
  showDialog.value = true
}

function openEdit(item: AnnouncementResponse) {
  editingItem.value = item
  form.value = {
    title: item.title,
    body: item.body,
    priority: item.priority,
    targetScope: item.targetScope,
    isPinned: item.isPinned,
    expiresAt: item.expiresAt ?? undefined,
  }
  showDialog.value = true
}

async function save() {
  try {
    if (editingItem.value) {
      await systemAdminApi.updateAnnouncement(editingItem.value.id, form.value)
      success('お知らせを更新しました')
    } else {
      await systemAdminApi.createAnnouncement(form.value)
      success('お知らせを作成しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  }
}

async function publish(id: number) {
  try {
    await systemAdminApi.publishAnnouncement(id)
    success('お知らせを公開しました')
    await load()
  } catch {
    showError('公開に失敗しました')
  }
}

async function remove(id: number) {
  try {
    await systemAdminApi.deleteAnnouncement(id)
    success('お知らせを削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

function onPage(event: { page: number }) {
  page.value = event.page
  load()
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="お知らせ管理" />
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <DataTable
      :value="items"
      :loading="loading"
      :lazy="true"
      :paginator="true"
      :rows="20"
      :total-records="totalRecords"
      :first="page * 20"
      data-key="id"
      striped-rows
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">お知らせがありません</div>
      </template>
      <Column field="title" header="タイトル" />
      <Column header="優先度" style="width: 100px">
        <template #body="{ data }">
          <Tag
            :value="data.priority"
            :severity="
              data.priority === 'HIGH' ? 'danger' : data.priority === 'LOW' ? 'secondary' : 'info'
            "
          />
        </template>
      </Column>
      <Column field="targetScope" header="対象" style="width: 100px" />
      <Column header="ピン留め" style="width: 80px">
        <template #body="{ data }">
          <i
            :class="data.isPinned ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-300'"
          />
        </template>
      </Column>
      <Column header="公開日" style="width: 160px">
        <template #body="{ data }">
          <span class="text-sm">{{
            data.publishedAt ? new Date(data.publishedAt).toLocaleString('ja-JP') : '未公開'
          }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 220px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="!data.publishedAt"
              label="公開"
              size="small"
              severity="success"
              @click="publish(data.id)"
            />
            <Button
              icon="pi pi-pencil"
              size="small"
              severity="secondary"
              text
              @click="openEdit(data)"
            />
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
      :header="editingItem ? 'お知らせ編集' : 'お知らせ作成'"
      :style="{ width: '600px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">タイトル</label>
          <InputText v-model="form.title" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">本文</label>
          <Textarea v-model="form.body" rows="5" class="w-full" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">優先度</label>
            <Select
              v-model="form.priority"
              :options="[
                { label: '低', value: 'LOW' },
                { label: '通常', value: 'NORMAL' },
                { label: '高', value: 'HIGH' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">対象</label>
            <Select
              v-model="form.targetScope"
              :options="[
                { label: '全員', value: 'ALL' },
                { label: '管理者', value: 'ADMIN' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isPinned" :binary="true" input-id="isPinned" />
          <label for="isPinned" class="text-sm">ピン留め</label>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
          <Button :label="editingItem ? '更新' : '作成'" @click="save" />
        </div>
      </template>
    </Dialog>
  </div>
</template>
