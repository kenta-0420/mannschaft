<script setup lang="ts">
import type { SnsFeedConfigResponse, FeedItem } from '~/types/sns-feed'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const { listFeeds, previewFeed } = useSnsFeedApi()

interface FeedItemWithMeta extends FeedItem {
  provider: string
  accountUsername: string
}

const loading = ref(true)
const refreshing = ref(false)
const allItems = ref<FeedItemWithMeta[]>([])

const MAX_DISPLAY = 10

const displayItems = computed(() => allItems.value.slice(0, MAX_DISPLAY))

function providerIcon(provider: string) {
  return provider === 'INSTAGRAM' ? 'pi pi-instagram' : 'pi pi-twitter'
}

function providerLabel(provider: string) {
  return provider === 'INSTAGRAM' ? 'Instagram' : 'X（旧Twitter）'
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('ja-JP', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function truncateCaption(caption: string | null, max = 150) {
  if (!caption) return ''
  return caption.length <= max ? caption : caption.slice(0, max) + '…'
}

async function loadFeeds() {
  try {
    const res = await listFeeds(props.scopeType, props.scopeId)
    const activeFeeds = (res.data as unknown as SnsFeedConfigResponse[]).filter((f) => f.isActive)

    const results = await Promise.allSettled(
      activeFeeds.map((feed) => previewFeed(props.scopeType, props.scopeId, feed.id)),
    )

    const items: FeedItemWithMeta[] = []
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        const preview = result.value.data
        preview.items.forEach((item) => {
          items.push({
            ...item,
            provider: activeFeeds[index]!.provider,
            accountUsername: activeFeeds[index]!.accountUsername,
          })
        })
      }
    })

    // 新しい投稿順にソート
    items.sort((a, b) => new Date(b.postedAt).getTime() - new Date(a.postedAt).getTime())
    allItems.value = items
  } catch {
    allItems.value = []
  }
}

async function init() {
  loading.value = true
  await loadFeeds()
  loading.value = false
}

async function refresh() {
  refreshing.value = true
  await loadFeeds()
  refreshing.value = false
}

onMounted(() => {
  init()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">SNSフィード</h2>
      <Button
        label="更新"
        icon="pi pi-refresh"
        size="small"
        text
        :loading="refreshing"
        @click="refresh"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div v-for="i in 3" :key="i" class="rounded-xl border border-surface-200 p-4 dark:border-surface-600">
        <div class="mb-3 flex items-center gap-2">
          <Skeleton shape="circle" size="2rem" />
          <Skeleton width="6rem" height="1rem" />
        </div>
        <Skeleton width="100%" height="160px" class="mb-3 rounded-lg" />
        <Skeleton width="100%" height="1rem" class="mb-2" />
        <Skeleton width="70%" height="1rem" class="mb-3" />
        <Skeleton width="5rem" height="0.75rem" />
      </div>
    </div>

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="displayItems.length === 0"
      icon="pi pi-share-alt"
      message="SNSフィードはまだ接続されていません"
    />

    <!-- 投稿一覧 -->
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <Card
        v-for="item in displayItems"
        :key="item.postId"
        class="overflow-hidden"
        :pt="{
          body: { class: 'p-4' },
          content: { class: 'p-0' },
        }"
      >
        <!-- プラットフォームバッジ -->
        <template #header>
          <div
            v-if="item.imageUrl"
            class="relative overflow-hidden"
            style="height: 200px"
          >
            <img
              :src="item.imageUrl"
              :alt="`@${item.accountUsername}の投稿`"
              class="h-full w-full object-cover"
            >
            <div
              class="absolute left-3 top-3 flex items-center gap-1 rounded-full bg-black/60 px-2 py-1 text-white"
            >
              <i :class="[providerIcon(item.provider), 'text-xs']" />
              <span class="text-xs">@{{ item.accountUsername }}</span>
            </div>
          </div>
          <div v-else class="flex items-center gap-2 border-b border-surface-100 px-4 py-3 dark:border-surface-600">
            <i :class="[providerIcon(item.provider), 'text-base text-surface-600']" />
            <span class="text-sm font-medium text-surface-600">{{ providerLabel(item.provider) }}</span>
            <span class="text-sm text-surface-400">@{{ item.accountUsername }}</span>
          </div>
        </template>

        <template #content>
          <p
            v-if="item.caption"
            class="mb-3 whitespace-pre-line text-sm leading-relaxed text-surface-700 dark:text-surface-200"
          >
            {{ truncateCaption(item.caption) }}
          </p>
          <p v-else class="mb-3 text-sm italic text-surface-400">（テキストなし）</p>

          <div class="flex items-center justify-between">
            <span class="text-xs text-surface-400">{{ formatDate(item.postedAt) }}</span>
            <a
              v-if="item.permalink"
              :href="item.permalink"
              target="_blank"
              rel="noopener noreferrer"
              class="flex items-center gap-1 text-xs text-primary hover:underline"
            >
              投稿を見る
              <i class="pi pi-external-link text-[10px]" />
            </a>
          </div>
        </template>
      </Card>
    </div>
  </div>
</template>
