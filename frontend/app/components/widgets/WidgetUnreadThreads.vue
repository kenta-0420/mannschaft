<script setup lang="ts">
const { getUnreadThreads } = useDashboardApi()

interface UnreadThread {
  id: number
  title: string
  type: 'BULLETIN' | 'CHAT'
  unreadCount: number
  lastMessageAt: string
  scopeName: string
}

const threads = ref<UnreadThread[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getUnreadThreads(8)
    threads.value = res.data
  }
  catch { threads.value = [] }
  finally { loading.value = false }
}

const totalUnread = computed(() => threads.value.reduce((sum, t) => sum + t.unreadCount, 0))

onMounted(load)
</script>

<template>
  <DashboardWidgetCard title="未読" icon="pi pi-envelope" :loading="loading" refreshable @refresh="load">
    <div v-if="threads.length > 0">
      <div class="mb-2">
        <Badge :value="totalUnread" severity="danger" />
      </div>
      <div class="space-y-2">
        <div
          v-for="thread in threads"
          :key="`${thread.type}-${thread.id}`"
          class="flex items-center gap-3 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
        >
          <i :class="thread.type === 'BULLETIN' ? 'pi pi-file-edit' : 'pi pi-comments'" class="text-surface-400" />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ thread.title }}</p>
            <p class="text-xs text-surface-400">{{ thread.scopeName }}</p>
          </div>
          <Badge :value="thread.unreadCount" severity="danger" />
        </div>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-check-circle" message="未読はありません" />
  </DashboardWidgetCard>
</template>
