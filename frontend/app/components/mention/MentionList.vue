<script setup lang="ts">
const api = useApi()
const notification = useNotification()
const { formatRelative } = useRelativeTime()

interface Mention {
  id: number
  mentionedBy: { id: number; displayName: string; avatarUrl: string | null }
  contentType: string
  contentId: number
  contentTitle: string | null
  contentSnippet: string
  url: string
  isRead: boolean
  createdAt: string
}

const mentions = ref<Mention[]>([])
const loading = ref(true)

const contentTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    POST: '投稿', MESSAGE: 'メッセージ', THREAD: 'スレッド', COMMENT: 'コメント',
  }
  return map[type] ?? type
}

async function loadMentions() {
  loading.value = true
  try {
    const res = await api<{ data: Mention[] }>('/api/v1/mentions')
    mentions.value = res.data
  } catch {
    notification.error('メンション一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function markAsRead(id: number) {
  try {
    await api(`/api/v1/mentions/${id}/read`, { method: 'POST' })
    const mention = mentions.value.find(m => m.id === id)
    if (mention) mention.isRead = true
  } catch (e) {
    console.error('既読マークに失敗しました', e)
  }
}

onMounted(loadMentions)
</script>

<template>
  <div>
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner />
    </div>

    <div v-else-if="mentions.length === 0" class="py-12 text-center text-surface-500">
      <i class="pi pi-at mb-2 text-4xl" />
      <p>メンションはありません</p>
    </div>

    <div v-else class="space-y-3">
      <NuxtLink
        v-for="mention in mentions"
        :key="mention.id"
        :to="mention.url"
        class="flex items-start gap-3 rounded-lg border p-4 transition-shadow hover:shadow-md"
        :class="mention.isRead
          ? 'border-surface-200 dark:border-surface-600'
          : 'border-primary/30 bg-primary/5'"
        @click="markAsRead(mention.id)"
      >
        <img
          v-if="mention.mentionedBy.avatarUrl"
          :src="mention.mentionedBy.avatarUrl"
          alt=""
          class="h-10 w-10 rounded-full object-cover"
        />
        <div v-else class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary">
          <i class="pi pi-user" />
        </div>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ mention.mentionedBy.displayName }}</span>
            <Badge :value="contentTypeLabel(mention.contentType)" severity="secondary" class="text-xs" />
            <span v-if="!mention.isRead" class="h-2 w-2 rounded-full bg-primary" />
          </div>
          <p class="mt-1 text-sm text-surface-600 dark:text-surface-400 truncate">
            {{ mention.contentSnippet }}
          </p>
          <p class="mt-1 text-xs text-surface-400">{{ formatRelative(mention.createdAt) }}</p>
        </div>
      </NuxtLink>
    </div>
  </div>
</template>
