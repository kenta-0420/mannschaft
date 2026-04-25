<script setup lang="ts">
import type { BlogPostResponse } from '~/types/cms'

const { getMyPosts } = useBlogApi()
const { captureQuiet } = useErrorReport()

const posts = ref<BlogPostResponse[]>([])
const loading = ref(true)
const showCreate = ref(false)

const statusLabel: Record<string, string> = {
  DRAFT: '下書き',
  PUBLISHED: '公開',
  SCHEDULED: '予約',
}
const statusSeverity: Record<string, string> = {
  DRAFT: 'secondary',
  PUBLISHED: 'success',
  SCHEDULED: 'info',
}

async function load() {
  loading.value = true
  try {
    const res = await getMyPosts({ size: 5 })
    posts.value = res.data
  } catch (error) {
    captureQuiet(error, { context: 'WidgetMyBlog: ブログ記事取得' })
    posts.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="マイブログ"
    icon="pi pi-book"
    to="/blog"
    :loading="loading"
    :col-span="2"
    refreshable
    @refresh="load"
  >
    <template #default>
      <div class="mb-3 flex justify-end">
        <Button label="新規作成" icon="pi pi-plus" size="small" @click="showCreate = true" />
      </div>

      <div v-if="posts.length > 0" class="space-y-2">
        <div
          v-for="post in posts"
          :key="post.id"
          class="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
          @click="navigateTo(`/blog/posts/${post.id}/edit`)"
        >
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ post.title }}</p>
            <p class="text-xs text-surface-400">
              {{ new Date(post.publishedAt || post.createdAt).toLocaleDateString('ja-JP') }}
            </p>
          </div>
          <Tag
            :value="statusLabel[post.status] ?? post.status"
            :severity="statusSeverity[post.status] ?? 'secondary'"
            rounded
          />
        </div>
      </div>
      <DashboardEmptyState v-else icon="pi pi-book" message="まだ記事がありません" />
    </template>
  </DashboardWidgetCard>

  <BlogCreateDialog v-model:visible="showCreate" />
</template>
