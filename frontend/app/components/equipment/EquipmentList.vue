<script setup lang="ts">
import type { EquipmentResponse } from '~/types/equipment'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  select: [equip: EquipmentResponse]
  create: []
}>()

const { getEquipmentList } = useEquipmentApi()
const { showError } = useNotification()

const items = ref<EquipmentResponse[]>([])
const loading = ref(false)

async function loadItems() {
  loading.value = true
  try {
    const res = await getEquipmentList(props.scopeType, props.scopeId)
    items.value = res.data
  } catch {
    showError('備品一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'AVAILABLE': return 'bg-green-100 text-green-700'
    case 'ASSIGNED': return 'bg-blue-100 text-blue-700'
    case 'MAINTENANCE': return 'bg-yellow-100 text-yellow-700'
    case 'RETIRED': return 'bg-surface-100 text-surface-500'
    default: return 'bg-surface-100'
  }
}

function getStatusLabel(status: string): string {
  const labels: Record<string, string> = { AVAILABLE: '利用可能', ASSIGNED: '貸出中', MAINTENANCE: 'メンテナンス', RETIRED: '廃棄' }
  return labels[status] || status
}

onMounted(() => loadItems())
defineExpose({ refresh: loadItems })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">備品管理</h2>
      <Button v-if="canManage" label="備品登録" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <button
        v-for="item in items"
        :key="item.id"
        class="flex items-center gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm"
        @click="emit('select', item)"
      >
        <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-surface-100">
          <img v-if="item.imageUrl" :src="item.imageUrl" class="h-full w-full rounded-lg object-cover" >
          <i v-else class="pi pi-box text-xl text-surface-400" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <span :class="getStatusClass(item.status)" class="rounded px-1.5 py-0.5 text-xs font-medium">{{ getStatusLabel(item.status) }}</span>
          </div>
          <h3 class="text-sm font-semibold">{{ item.name }}</h3>
          <div class="text-xs text-surface-400">
            <span v-if="item.category">{{ item.category }} ・ </span>
            <span>残 {{ item.availableQuantity }}/{{ item.quantity }}</span>
            <span v-if="item.assignedTo"> ・ {{ item.assignedTo.displayName }}</span>
          </div>
        </div>
      </button>
    </div>

    <div v-if="!loading && items.length === 0" class="py-12 text-center">
      <i class="pi pi-box mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">備品がありません</p>
    </div>
  </div>
</template>
