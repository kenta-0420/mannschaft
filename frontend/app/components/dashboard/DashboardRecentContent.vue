<script setup lang="ts">
import type { BlogPostResponse } from '~/types/cms'
import type { BulletinThreadResponse } from '~/types/bulletin'

const props = defineProps<{
  scopeType: 'personal' | 'team' | 'organization'
  scopeId?: number
  /** team/org のベースパス。例: /teams/1 or /organizations/2 */
  basePath?: string
}>()

const { getPosts, getFeed } = useBlogApi()
const { getThreads } = useBulletinApi()
const { relativeTime } = useRelativeTime()

const recentPosts = ref<BlogPostResponse[]>([])
const recentThreads = ref<BulletinThreadResponse[]>([])
const postsLoading = ref(false)
const threadsLoading = ref(false)

const isScopedContent = computed(() => props.scopeType !== 'personal')

const scopeTypeUpper = computed(() => (props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION'))

const blogAllLink = computed(() => (props.basePath ? `${props.basePath}/blog` : null))

const bulletinAllLink = computed(() => (props.basePath ? `${props.basePath}/bulletin` : null))

const priorityConfig: Record<string, { label: string; class: string }> = {
  CRITICAL: { label: '緊急', class: 'bg-red-100 text-red-700' },
  IMPORTANT: { label: '重要', class: 'bg-orange-100 text-orange-700' },
  WARNING: { label: '注意', class: 'bg-yellow-100 text-yellow-700' },
  INFO: { label: '通知', class: 'bg-blue-100 text-blue-700' },
  LOW: { label: '低', class: 'bg-surface-100 text-surface-500' },
}

async function loadPosts() {
  postsLoading.value = true
  try {
    if (isScopedContent.value && props.scopeId) {
      const res = await getPosts({
        scope_type: scopeTypeUpper.value,
        scope_id: props.scopeId,
        page: 0,
        size: 3,
      })
      recentPosts.value = res.data.filter((p) => p.status === 'PUBLISHED')
    } else {
      const res = await getFeed({ page: 0, size: 3 })
      recentPosts.value = res.data
    }
  } catch {
    recentPosts.value = []
  } finally {
    postsLoading.value = false
  }
}

async function loadThreads() {
  if (!isScopedContent.value || !props.scopeId) return
  threadsLoading.value = true
  try {
    const res = await getThreads({
      scopeType: scopeTypeUpper.value,
      scopeId: props.scopeId,
      isArchived: false,
      page: 0,
      size: 5,
    })
    recentThreads.value = res.data
  } catch {
    recentThreads.value = []
  } finally {
    threadsLoading.value = false
  }
}

onMounted(() => {
  loadPosts()
  loadThreads()
})
</script>

<template>
  <div class="space-y-4">
    <!-- ブログ・お知らせセクション -->
    <div
      class="rounded-xl border border-surface-200 bg-surface-0 p-5 dark:border-surface-700 dark:bg-surface-800"
    >
      <div class="mb-4 flex items-center justify-between">
        <h2
          class="flex items-center gap-2 text-base font-semibold text-surface-700 dark:text-surface-200"
        >
          <i class="pi pi-book text-primary" />
          {{ isScopedContent ? 'ブログ・お知らせ' : '最新記事' }}
        </h2>
        <NuxtLink v-if="blogAllLink" :to="blogAllLink" class="text-sm text-primary hover:underline">
          すべて見る
        </NuxtLink>
      </div>

      <div v-if="postsLoading" class="flex justify-center py-6">
        <ProgressSpinner style="width: 32px; height: 32px" />
      </div>

      <div v-else-if="recentPosts.length === 0" class="py-6 text-center text-sm text-surface-400">
        <i class="pi pi-book mb-2 block text-2xl" />
        {{
          isScopedContent ? '公開済みの記事はまだありません' : 'フォロー中のブログ記事はありません'
        }}
      </div>

      <div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <NuxtLink
          v-for="post in recentPosts"
          :key="post.id"
          :to="blogAllLink ?? '#'"
          class="group overflow-hidden rounded-lg border border-surface-200 bg-surface-50 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
        >
          <img
            v-if="post.coverImageUrl"
            :src="post.coverImageUrl"
            class="h-32 w-full object-cover"
          />
          <div class="p-3">
            <h3
              class="mb-1 text-sm font-semibold text-surface-800 line-clamp-2 group-hover:text-primary dark:text-surface-100"
            >
              {{ post.title }}
            </h3>
            <p v-if="post.excerpt" class="mb-2 text-xs text-surface-400 line-clamp-2">
              {{ post.excerpt }}
            </p>
            <div class="flex items-center gap-2 text-xs text-surface-400">
              <span>{{ post.author.displayName }}</span>
              <span>{{ relativeTime(post.publishedAt || post.createdAt) }}</span>
            </div>
          </div>
        </NuxtLink>
      </div>
    </div>

    <!-- 掲示板・お知らせセクション (team / org のみ) -->
    <div
      v-if="isScopedContent"
      class="rounded-xl border border-surface-200 bg-surface-0 p-5 dark:border-surface-700 dark:bg-surface-800"
    >
      <div class="mb-4 flex items-center justify-between">
        <h2
          class="flex items-center gap-2 text-base font-semibold text-surface-700 dark:text-surface-200"
        >
          <i class="pi pi-megaphone text-primary" />
          掲示板・お知らせ
        </h2>
        <NuxtLink
          v-if="bulletinAllLink"
          :to="bulletinAllLink"
          class="text-sm text-primary hover:underline"
        >
          すべて見る
        </NuxtLink>
      </div>

      <div v-if="threadsLoading" class="flex justify-center py-6">
        <ProgressSpinner style="width: 32px; height: 32px" />
      </div>

      <div v-else-if="recentThreads.length === 0" class="py-6 text-center text-sm text-surface-400">
        <i class="pi pi-clipboard mb-2 block text-2xl" />
        掲示板のスレッドはまだありません
      </div>

      <div v-else class="divide-y divide-surface-100 dark:divide-surface-700">
        <NuxtLink
          v-for="thread in recentThreads"
          :key="thread.id"
          :to="bulletinAllLink ?? '#'"
          class="flex items-start gap-3 py-3 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
        >
          <i
            v-if="thread.isPinned"
            class="pi pi-thumbtack mt-0.5 shrink-0 text-sm text-orange-500"
          />
          <div class="min-w-0 flex-1">
            <div class="mb-1 flex flex-wrap items-center gap-2">
              <span
                v-if="thread.priority !== 'INFO'"
                :class="priorityConfig[thread.priority]?.class"
                class="rounded px-1.5 py-0.5 text-xs font-medium"
              >
                {{ priorityConfig[thread.priority]?.label }}
              </span>
              <span class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
                {{ thread.title }}
              </span>
            </div>
            <div class="flex items-center gap-3 text-xs text-surface-400">
              <span>{{ thread.author.displayName }}</span>
              <span>{{ relativeTime(thread.createdAt) }}</span>
              <span v-if="thread.replyCount > 0">
                <i class="pi pi-comment" /> {{ thread.replyCount }}
              </span>
            </div>
          </div>
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
