<script setup lang="ts">
defineProps<{
  teamId: number
}>()

const shiftApi = useShiftApi()
const notification = useNotification()

interface Position {
  id: number
  name: string
  description: string | null
  requiredCount: number
  color: string | null
  sortOrder: number
}

const positions = ref<Position[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editing = ref<Position | null>(null)
const form = ref({ name: '', description: '', requiredCount: 1, color: '#6366f1' })

const colorOptions = [
  '#6366f1',
  '#ef4444',
  '#22c55e',
  '#f59e0b',
  '#3b82f6',
  '#ec4899',
  '#8b5cf6',
  '#14b8a6',
]

async function load() {
  loading.value = true
  try {
    const res = await shiftApi.getPositions()
    positions.value = res.data as Position[]
  } catch {
    positions.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { name: '', description: '', requiredCount: 1, color: '#6366f1' }
  showDialog.value = true
}

function openEdit(pos: Position) {
  editing.value = pos
  form.value = {
    name: pos.name,
    description: pos.description ?? '',
    requiredCount: pos.requiredCount,
    color: pos.color ?? '#6366f1',
  }
  showDialog.value = true
}

async function save() {
  if (!form.value.name.trim()) return
  try {
    if (editing.value) {
      await shiftApi.updatePosition(editing.value.id, form.value)
    } else {
      await shiftApi.createPosition(form.value)
    }
    notification.success('ポジションを保存しました')
    showDialog.value = false
    await load()
  } catch {
    notification.error('保存に失敗しました')
  }
}

async function remove(id: number) {
  if (!confirm('このポジションを削除しますか？')) return
  await shiftApi.deletePosition(id)
  notification.success('削除しました')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">ポジション管理</h3>
      <Button label="追加" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="positions.length > 0" class="space-y-2">
      <div
        v-for="pos in positions"
        :key="pos.id"
        class="flex items-center gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
      >
        <div class="h-4 w-4 rounded-full" :style="{ backgroundColor: pos.color ?? '#6366f1' }" />
        <div class="min-w-0 flex-1">
          <p class="font-medium">{{ pos.name }}</p>
          <p class="text-xs text-surface-500">必要人数: {{ pos.requiredCount }}</p>
        </div>
        <Button icon="pi pi-pencil" text rounded size="small" @click="openEdit(pos)" />
        <Button
          icon="pi pi-trash"
          text
          rounded
          size="small"
          severity="danger"
          @click="remove(pos.id)"
        />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-id-card" message="ポジションはまだありません" />

    <Dialog
      v-model:visible="showDialog"
      :header="editing ? 'ポジション編集' : 'ポジション追加'"
      :style="{ width: '400px' }"
      modal
    >
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">名前</label>
          <InputText v-model="form.name" class="w-full" placeholder="例: レジ担当" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <InputText v-model="form.description" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">必要人数</label>
          <InputNumber v-model="form.requiredCount" :min="1" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">色</label>
          <div class="flex gap-2">
            <button
              v-for="c in colorOptions"
              :key="c"
              class="h-8 w-8 rounded-full border-2"
              :class="
                form.color === c ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'
              "
              :style="{ backgroundColor: c }"
              @click="form.color = c"
            />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button label="保存" icon="pi pi-check" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
