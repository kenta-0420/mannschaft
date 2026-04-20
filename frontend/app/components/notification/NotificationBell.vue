<script setup lang="ts">
const { getUnreadCount } = useNotificationApi()
const { getChannels } = useChatApi()
const api = useApi()
const router = useRouter()
const popover = ref()

interface Mention {
  id: number
  isRead: boolean
}

const notifCount = ref(0)
const chatCount = ref(0)
const mentionCount = ref(0)

const totalCount = computed(() => notifCount.value + chatCount.value + mentionCount.value)

async function fetchCounts() {
  await Promise.allSettled([
    getUnreadCount()
      .then((r) => {
        notifCount.value = r.data.total
      })
      .catch(() => {}),
    getChannels()
      .then((r) => {
        chatCount.value = (r.data as { unreadCount?: number }[]).reduce(
          (sum: number, ch: { unreadCount?: number }) => sum + (ch.unreadCount ?? 0),
          0,
        )
      })
      .catch(() => {}),
    api<{ data: Mention[] }>('/api/v1/mentions')
      .then((r) => {
        mentionCount.value = r.data.filter((m: Mention) => !m.isRead).length
      })
      .catch(() => {}),
  ])
}

function navigate(to: string) {
  popover.value?.hide()
  router.push(to)
}

let timer: ReturnType<typeof setInterval>
onMounted(() => {
  fetchCounts()
  timer = setInterval(fetchCounts, 60000)
})
onUnmounted(() => clearInterval(timer))

defineExpose({ refresh: fetchCounts })
</script>

<template>
  <div class="relative">
    <Button
      v-tooltip.bottom="'通知'"
      icon="pi pi-bell"
      text
      rounded
      severity="secondary"
      @click="popover?.toggle($event)"
    />
    <Badge
      v-if="totalCount > 0"
      :value="totalCount > 99 ? '99+' : totalCount"
      severity="danger"
      class="absolute -right-1 -top-1 pointer-events-none"
    />

    <Popover ref="popover">
      <div class="flex flex-col gap-1 py-1" style="min-width: 200px">
        <!-- 通知 -->
        <button class="notif-row" @click="navigate('/notifications')">
          <span class="notif-icon bg-amber-50 text-amber-500"
            ><i class="pi pi-bell text-xs"
          /></span>
          <span class="flex-1 text-left text-sm">通知</span>
          <Badge v-if="notifCount > 0" :value="notifCount" severity="danger" />
          <span v-else class="text-xs text-surface-400">なし</span>
        </button>

        <!-- チャット -->
        <button class="notif-row" @click="navigate('/chat')">
          <span class="notif-icon bg-green-50 text-green-500"
            ><i class="pi pi-comment text-xs"
          /></span>
          <span class="flex-1 text-left text-sm">チャット</span>
          <Badge v-if="chatCount > 0" :value="chatCount" severity="danger" />
          <span v-else class="text-xs text-surface-400">なし</span>
        </button>

        <!-- メンション -->
        <button class="notif-row" @click="navigate('/notifications?tab=mention')">
          <span class="notif-icon bg-blue-50 text-blue-500"><i class="pi pi-at text-xs" /></span>
          <span class="flex-1 text-left text-sm">メンション</span>
          <Badge v-if="mentionCount > 0" :value="mentionCount" severity="danger" />
          <span v-else class="text-xs text-surface-400">なし</span>
        </button>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.notif-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
  transition: background 0.15s;
  cursor: pointer;
  background: transparent;
  border: none;
  width: 100%;
}
.notif-row:hover {
  background: var(--p-surface-100);
}
.notif-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
