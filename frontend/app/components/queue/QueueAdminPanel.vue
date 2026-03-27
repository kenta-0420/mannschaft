<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const queueApi = useQueueApi()
const notification = useNotification()

interface Counter { id: number; name: string; isActive: boolean; currentTicket: string | null }

const counters = ref<Counter[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await queueApi.getCounters(props.scopeType, props.scopeId)
    counters.value = res.data as Counter[]
  }
  catch { counters.value = [] }
  finally { loading.value = false }
}

async function callNext(counterId: number) {
  try {
    const res = await queueApi.callNextTicket(props.scopeType, props.scopeId, counterId)
    const ticket = res.data as { ticketNumber: string }
    notification.success(`${ticket.ticketNumber} を呼び出しました`)
    await load()
  }
  catch { notification.error('呼び出しに失敗しました') }
}

onMounted(load)
</script>

<template>
  <div>
    <h3 class="mb-3 text-lg font-semibold">窓口操作</h3>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="4rem" class="mb-2" /></div>
    <div v-else-if="counters.length > 0" class="space-y-3">
      <div
        v-for="counter in counters"
        :key="counter.id"
        class="flex items-center gap-4 rounded-lg border border-surface-200 p-4 dark:border-surface-700"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">{{ counter.name }}</p>
          <p v-if="counter.currentTicket" class="text-sm text-primary">対応中: {{ counter.currentTicket }}</p>
          <p v-else class="text-sm text-surface-400">待機中</p>
        </div>
        <Button label="次を呼ぶ" icon="pi pi-megaphone" size="small" @click="callNext(counter.id)" />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-desktop" message="窓口はまだ設定されていません" />
  </div>
</template>
