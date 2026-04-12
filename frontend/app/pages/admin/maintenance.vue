<script setup lang="ts">
import type {
  MaintenanceScheduleResponse,
  CreateMaintenanceScheduleRequest,
} from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const items = ref<MaintenanceScheduleResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<MaintenanceScheduleResponse | null>(null)
const form = ref<CreateMaintenanceScheduleRequest>({
  title: '',
  message: '',
  mode: 'PARTIAL',
  startsAt: '',
  endsAt: '',
})

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getMaintenanceSchedules()
    items.value = res.data
  } catch {
    showError('メンテナンス予定の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { title: '', message: '', mode: 'PARTIAL', startsAt: '', endsAt: '' }
  showDialog.value = true
}

function openEdit(item: MaintenanceScheduleResponse) {
  editingItem.value = item
  form.value = {
    title: item.title,
    message: item.message,
    mode: item.mode,
    startsAt: item.startsAt,
    endsAt: item.endsAt,
  }
  showDialog.value = true
}

async function save() {
  try {
    if (editingItem.value) {
      await systemAdminApi.updateMaintenanceSchedule(editingItem.value.id, form.value)
      success('メンテナンス予定を更新しました')
    } else {
      await systemAdminApi.createMaintenanceSchedule(form.value)
      success('メンテナンス予定を作成しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  }
}

async function activate(id: number) {
  try {
    await systemAdminApi.activateMaintenanceSchedule(id)
    success('メンテナンスを開始しました')
    await load()
  } catch {
    showError('開始に失敗しました')
  }
}

async function complete(id: number) {
  try {
    await systemAdminApi.completeMaintenanceSchedule(id)
    success('メンテナンスを完了しました')
    await load()
  } catch {
    showError('完了に失敗しました')
  }
}

async function remove(id: number) {
  try {
    await systemAdminApi.deleteMaintenanceSchedule(id)
    success('メンテナンス予定を削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

function statusSeverity(status: string) {
  switch (status) {
    case 'SCHEDULED':
      return 'info'
    case 'ACTIVE':
      return 'warn'
    case 'COMPLETED':
      return 'success'
    case 'CANCELLED':
      return 'secondary'
    default:
      return 'secondary'
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="メンテナンス予定管理" />
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="items" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">メンテナンス予定がありません</div>
      </template>
      <Column field="title" header="タイトル" />
      <Column field="mode" header="モード" style="width: 100px" />
      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="開始日時" style="width: 160px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.startsAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>
      <Column header="終了日時" style="width: 160px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.endsAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 280px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="data.status === 'SCHEDULED'"
              label="開始"
              size="small"
              severity="warn"
              @click="activate(data.id)"
            />
            <Button
              v-if="data.status === 'ACTIVE'"
              label="完了"
              size="small"
              severity="success"
              @click="complete(data.id)"
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
      :header="editingItem ? 'メンテナンス予定編集' : 'メンテナンス予定作成'"
      :style="{ width: '600px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">タイトル</label>
          <InputText v-model="form.title" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">メッセージ</label>
          <Textarea v-model="form.message" rows="3" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">モード</label>
          <Select
            v-model="form.mode"
            :options="[
              { label: '部分停止', value: 'PARTIAL' },
              { label: '全面停止', value: 'FULL' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">開始日時</label>
            <InputText v-model="form.startsAt" type="datetime-local" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日時</label>
            <InputText v-model="form.endsAt" type="datetime-local" class="w-full" />
          </div>
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
