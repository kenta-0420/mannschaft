<script setup lang="ts">
const { getNotices, markNoticeRead, markAllNoticesRead } = useDashboardApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

interface Notice {
  id: number
  type: string
  title: string
  message: string | null
  isRead: boolean
  createdAt: string
  linkUrl: string | null
}

const notices = ref<Notice[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getNotices({ limit: 5 })
    notices.value = res.data.items.map((n) => ({
      id: n.id,
      type: n.type,
      title: n.title,
      message: n.body,
      isRead: n.is_read,
      createdAt: n.created_at,
      linkUrl: n.action_url,
    }))
  } catch (error) {
    captureQuiet(error, { context: 'WidgetNotices: お知らせ取得' })
    notices.value = []
  } finally {
    loading.value = false
  }
}

async function onMarkRead(id: number) {
  await markNoticeRead(id)
  const item = notices.value.find((n) => n.id === id)
  if (item) item.isRead = true
}

async function onMarkAllRead() {
  await markAllNoticesRead()
  notices.value.forEach((n) => {
    n.isRead = true
  })
  notification.success('全て既読にしました')
}

const unreadCount = computed(() => notices.value.filter((n) => !n.isRead).length)

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="お知らせ"
    icon="pi pi-bell"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="notices.length > 0">
      <div class="mb-2 flex items-center justify-between">
        <Badge v-if="unreadCount > 0" :value="unreadCount" severity="danger" />
        <Button v-if="unreadCount > 0" label="全て既読" text size="small" @click="onMarkAllRead" />
      </div>
      <div class="divide-y divide-surface-100 dark:divide-surface-700">
        <div
          v-for="notice in notices"
          :key="notice.id"
          class="flex items-start gap-3 py-2"
          :class="{ 'opacity-60': notice.isRead }"
        >
          <div
            class="mt-1 h-2 w-2 shrink-0 rounded-full"
            :class="notice.isRead ? 'bg-surface-300' : 'bg-primary'"
          />
          <div class="min-w-0 flex-1">
            <NuxtLink
              v-if="notice.linkUrl"
              :to="notice.linkUrl"
              class="text-sm font-medium hover:text-primary"
              @click="onMarkRead(notice.id)"
            >
              {{ notice.title }}
            </NuxtLink>
            <p v-else class="text-sm font-medium" @click="onMarkRead(notice.id)">
              {{ notice.title }}
            </p>
            <p v-if="notice.message" class="truncate text-xs text-surface-500">
              {{ notice.message }}
            </p>
          </div>
        </div>
      </div>
      <NuxtLink
        to="/notifications"
        class="mt-2 block text-center text-xs text-primary hover:underline"
      >
        すべて表示
      </NuxtLink>
    </div>
    <DashboardEmptyState v-else icon="pi pi-bell-slash" message="お知らせはありません" />
  </DashboardWidgetCard>
</template>
