<script setup lang="ts">
const { getUnreadThreads } = useDashboardApi()
const { captureQuiet } = useErrorReport()

const totalBulletin = ref(0)
const totalChat = ref(0)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getUnreadThreads()
    totalBulletin.value = res.data.total_unread_bulletin
    totalChat.value = res.data.total_unread_chat
  } catch (error) {
    captureQuiet(error, { context: 'WidgetUnreadThreads: 未読スレッド取得' })
    totalBulletin.value = 0
    totalChat.value = 0
  } finally {
    loading.value = false
  }
}

const totalUnread = computed(() => totalBulletin.value + totalChat.value)

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="未読"
    icon="pi pi-envelope"
    to="/chat"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="totalUnread > 0" class="space-y-3">
      <div class="mb-2">
        <Badge :value="totalUnread" severity="danger" />
      </div>
      <div
        v-if="totalBulletin > 0"
        class="flex items-center gap-3 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50 cursor-pointer"
        @click="navigateTo('/teams')"
      >
        <i class="pi pi-file-edit text-surface-400" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">掲示板・お知らせ</p>
          <p class="text-xs text-surface-400">チーム・組織の未読スレッド</p>
        </div>
        <Badge :value="totalBulletin" severity="danger" />
      </div>
      <div
        v-if="totalChat > 0"
        class="flex items-center gap-3 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50 cursor-pointer"
        @click="navigateTo('/chat')"
      >
        <i class="pi pi-comments text-surface-400" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">チャット</p>
          <p class="text-xs text-surface-400">未読メッセージ</p>
        </div>
        <Badge :value="totalChat" severity="danger" />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-check-circle" message="未読はありません" />
  </DashboardWidgetCard>
</template>
