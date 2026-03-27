<script setup lang="ts">
const props = defineProps<{
  teamId: number
}>()

const reservationApi = useReservationApi()
const notification = useNotification()

interface Line { id: number; name: string; description: string | null; capacity: number; isActive: boolean; sortOrder: number }

const lines = ref<Line[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingLine = ref<Line | null>(null)
const form = ref({ name: '', description: '', capacity: 1 })

async function loadLines() {
  loading.value = true
  try {
    const res = await reservationApi.getLines(props.teamId)
    lines.value = res.data as Line[]
  }
  catch { lines.value = [] }
  finally { loading.value = false }
}

function openCreate() {
  editingLine.value = null
  form.value = { name: '', description: '', capacity: 1 }
  showDialog.value = true
}

function openEdit(line: Line) {
  editingLine.value = line
  form.value = { name: line.name, description: line.description ?? '', capacity: line.capacity }
  showDialog.value = true
}

async function save() {
  if (!form.value.name.trim()) return
  try {
    if (editingLine.value) {
      await reservationApi.updateLine(props.teamId, editingLine.value.id, form.value)
      notification.success('ラインを更新しました')
    } else {
      await reservationApi.createLine(props.teamId, form.value)
      notification.success('ラインを作成しました')
    }
    showDialog.value = false
    await loadLines()
  }
  catch { notification.error('保存に失敗しました') }
}

async function remove(lineId: number) {
  if (!confirm('このラインを削除しますか？')) return
  await reservationApi.deleteLine(props.teamId, lineId)
  notification.success('ラインを削除しました')
  await loadLines()
}

onMounted(loadLines)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">予約ライン管理</h3>
      <Button label="ライン追加" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="lines.length > 0" class="space-y-2">
      <div v-for="line in lines" :key="line.id" class="flex items-center gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700">
        <div class="min-w-0 flex-1">
          <p class="font-medium">{{ line.name }}</p>
          <p class="text-xs text-surface-500">定員: {{ line.capacity }} | {{ line.isActive ? '有効' : '無効' }}</p>
        </div>
        <Button icon="pi pi-pencil" text rounded size="small" @click="openEdit(line)" />
        <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="remove(line.id)" />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-list" message="ラインはまだありません" />

    <Dialog v-model:visible="showDialog" :header="editingLine ? 'ライン編集' : 'ライン作成'" :style="{ width: '400px' }" modal>
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">名前</label>
          <InputText v-model="form.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <InputText v-model="form.description" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">定員</label>
          <InputNumber v-model="form.capacity" :min="1" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button label="保存" icon="pi pi-check" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
