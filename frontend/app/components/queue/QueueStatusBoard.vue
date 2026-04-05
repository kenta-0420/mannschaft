<script setup lang="ts">
const props = defineProps<{
  teamId: number
}>()

const queueApi = useQueueApi()

interface CounterStatus {
  id: number
  name: string
  isActive: boolean
  currentTicket: string | null
  waitingCount: number
  estimatedWaitMinutes: number
}
interface Status {
  counters: CounterStatus[]
  totalWaiting: number
  averageWaitMinutes: number
}

const status = ref<Status | null>(null)
const loading = ref(true)
let pollInterval: ReturnType<typeof setInterval> | null = null

async function load() {
  try {
    const res = await queueApi.getQueueStatus(props.teamId)
    status.value = res.data as Status
  } catch {
    /* silent */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  pollInterval = setInterval(load, 10000) // 10秒ポーリング
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
})
</script>

<template>
  <div>
    <div v-if="loading" class="space-y-3">
      <Skeleton height="4rem" />
      <div class="grid grid-cols-3 gap-3">
        <Skeleton v-for="i in 3" :key="i" height="6rem" />
      </div>
    </div>
    <div v-else-if="status">
      <!-- サマリー -->
      <div class="mb-6 grid grid-cols-2 gap-4">
        <div
          class="rounded-xl border border-surface-300 bg-surface-0 p-4 text-center dark:border-surface-600 dark:bg-surface-800"
        >
          <p class="text-3xl font-bold text-primary">{{ status.totalWaiting }}</p>
          <p class="text-sm text-surface-500">待ち人数</p>
        </div>
        <div
          class="rounded-xl border border-surface-300 bg-surface-0 p-4 text-center dark:border-surface-600 dark:bg-surface-800"
        >
          <p class="text-3xl font-bold text-primary">{{ status.averageWaitMinutes }}分</p>
          <p class="text-sm text-surface-500">平均待ち時間</p>
        </div>
      </div>

      <!-- カウンター状況 -->
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="counter in status.counters"
          :key="counter.id"
          class="rounded-xl border p-4"
          :class="
            counter.isActive
              ? 'border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-900/20'
              : 'border-surface-200 bg-surface-50 dark:border-surface-600 dark:bg-surface-800'
          "
        >
          <div class="mb-2 flex items-center justify-between">
            <h4 class="font-semibold">{{ counter.name }}</h4>
            <Tag
              :value="counter.isActive ? '稼働中' : '停止'"
              :severity="counter.isActive ? 'success' : 'secondary'"
              rounded
            />
          </div>
          <div
            v-if="counter.currentTicket"
            class="mb-2 rounded-lg bg-white p-2 text-center dark:bg-surface-900"
          >
            <p class="text-xs text-surface-500">現在対応中</p>
            <p class="text-2xl font-bold text-primary">{{ counter.currentTicket }}</p>
          </div>
          <div class="flex justify-between text-xs text-surface-500">
            <span>待ち: {{ counter.waitingCount }}人</span>
            <span>約{{ counter.estimatedWaitMinutes }}分</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
