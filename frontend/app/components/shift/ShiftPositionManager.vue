<script setup lang="ts">
import type { ShiftPositionResponse } from '~/types/shift'

const props = defineProps<{
  teamId: number
}>()

const shiftApi = useShiftApi()
const notification = useNotification()

const positions = ref<ShiftPositionResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editing = ref<ShiftPositionResponse | null>(null)
const form = ref({ name: '', displayOrder: 1 })

async function load() {
  loading.value = true
  try {
    const res = await shiftApi.getPositions(props.teamId)
    positions.value = res.data
  } catch {
    positions.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { name: '', displayOrder: positions.value.length + 1 }
  showDialog.value = true
}

function openEdit(pos: ShiftPositionResponse) {
  editing.value = pos
  form.value = {
    name: pos.name,
    displayOrder: pos.displayOrder,
  }
  showDialog.value = true
}

async function save() {
  if (!form.value.name.trim()) return
  try {
    if (editing.value) {
      await shiftApi.updatePosition(editing.value.id, { name: form.value.name, displayOrder: form.value.displayOrder })
    } else {
      await shiftApi.createPosition(props.teamId, { name: form.value.name, displayOrder: form.value.displayOrder })
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
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <div class="h-4 w-4 rounded-full" :style="{ backgroundColor: pos.color ?? '#6366f1' }" />
        <div class="min-w-0 flex-1">
          <p class="font-medium">{{ pos.name }}</p>
          <p class="text-xs text-surface-500">表示順: {{ pos.displayOrder }}</p>
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
          <label class="mb-1 block text-sm font-medium">表示順</label>
          <InputNumber v-model="form.displayOrder" :min="1" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button label="保存" icon="pi pi-check" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
