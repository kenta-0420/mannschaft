<script setup lang="ts">
import type { BlogPostResponse, BlogPostStatus } from '~/types/cms'

definePageMeta({ middleware: 'auth' })

const { getMyPosts, deleteMyPost, publishMyPost } = useBlogApi()
const { handleError } = useErrorHandler()
const { success, error: showError } = useNotification()

type TabKey = 'all' | BlogPostStatus

const activeTab = ref<TabKey>('all')
const posts = ref<BlogPostResponse[]>([])
const loading = ref(false)
const showCreateDialog = ref(false)
const deletingId = ref<number | null>(null)
const publishingId = ref<number | null>(null)

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'all', label: 'すべて' },
  { key: 'DRAFT', label: '下書き' },
  { key: 'PUBLISHED', label: '公開済み' },
  { key: 'SCHEDULED', label: '予約済み' },
]

const filteredPosts = computed<BlogPostResponse[]>(() => {
  if (activeTab.value === 'all') return posts.value
  return posts.value.filter((p) => p.status === activeTab.value)
})

async function loadPosts() {
  loading.value = true
  try {
    const res = await getMyPosts({ page: 0, size: 100 })
    posts.value = res.data
  } catch (error) {
    handleError(error)
  } finally {
    loading.value = false
  }
}

async function handleDelete(postId: number) {
  deletingId.value = postId
  try {
    await deleteMyPost(postId)
    posts.value = posts.value.filter((p) => p.id !== postId)
    success('記事を削除しました')
  } catch (error) {
    handleError(error)
  } finally {
    deletingId.value = null
  }
}

async function handleTogglePublish(post: BlogPostResponse) {
  publishingId.value = post.id
  try {
    const newStatus = post.status === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED'
    await publishMyPost(post.id, { status: newStatus })
    const idx = posts.value.findIndex((p) => p.id === post.id)
    if (idx >= 0 && posts.value[idx]) {
      posts.value[idx] = { ...posts.value[idx]!, status: newStatus as BlogPostStatus }
    }
    success(newStatus === 'PUBLISHED' ? '記事を公開しました' : '下書きに戻しました')
  } catch (error) {
    handleError(error)
  } finally {
    publishingId.value = null
  }
}

function getStatusClass(status: BlogPostStatus): string {
  switch (status) {
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600 dark:bg-surface-800 dark:text-surface-300'
    case 'PUBLISHED':
      return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'SCHEDULED':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
    case 'ARCHIVED':
      return 'bg-surface-100 text-surface-500'
    default:
      return 'bg-surface-100 text-surface-600'
  }
}

function getStatusLabel(status: BlogPostStatus): string {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'PUBLISHED':
      return '公開済み'
    case 'SCHEDULED':
      return '予約済み'
    case 'ARCHIVED':
      return 'アーカイブ'
    default:
      return status
  }
}

onMounted(() => loadPosts())
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 py-8">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center justify-between">
      <PageHeader title="マイブログ" />
      <div class="flex items-center gap-2">
        <NuxtLink to="/me/blog/settings">
          <Button
            icon="pi pi-cog"
            :label="$t('blog.post.settings')"
            text
            severity="secondary"
          />
        </NuxtLink>
        <Button
          icon="pi pi-plus"
          label="新規記事"
          @click="showCreateDialog = true"
        />
      </div>
    </div>

    <!-- タブ -->
    <div class="mb-6 flex border-b border-surface-200 dark:border-surface-700">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="[
          'px-4 py-2 text-sm font-medium transition-colors',
          activeTab === tab.key
            ? 'border-b-2 border-primary-500 text-primary-600 dark:text-primary-400'
            : 'text-surface-500 hover:text-surface-700 dark:text-surface-400 dark:hover:text-surface-200',
        ]"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
        <span class="ml-1 rounded-full bg-surface-100 px-1.5 py-0.5 text-xs dark:bg-surface-800">
          {{ tab.key === 'all' ? posts.length : posts.filter((p) => p.status === tab.key).length }}
        </span>
      </button>
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 記事一覧 -->
    <template v-else-if="filteredPosts.length > 0">
      <div class="space-y-3">
        <div
          v-for="post in filteredPosts"
          :key="post.id"
          class="flex items-center gap-4 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900"
        >
          <!-- サムネイル -->
          <img
            v-if="post.coverImageUrl"
            :src="post.coverImageUrl"
            :alt="post.title"
            class="h-16 w-24 flex-shrink-0 rounded-lg object-cover"
          />
          <div
            v-else
            class="flex h-16 w-24 flex-shrink-0 items-center justify-center rounded-lg bg-surface-100 dark:bg-surface-800"
          >
            <i class="pi pi-book text-2xl text-surface-300" />
          </div>

          <!-- 記事情報 -->
          <div class="min-w-0 flex-1">
            <div class="mb-1 flex items-center gap-2">
              <span
                :class="getStatusClass(post.status)"
                class="rounded px-1.5 py-0.5 text-xs font-medium"
              >
                {{ getStatusLabel(post.status) }}
              </span>
              <span
                v-for="tag in post.tags.slice(0, 2)"
                :key="tag.id"
                class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-800"
              >
                #{{ tag.name }}
              </span>
            </div>
            <h3 class="truncate text-sm font-semibold text-surface-800 dark:text-surface-100">
              {{ post.title }}
            </h3>
            <p v-if="post.publishedAt" class="mt-0.5 text-xs text-surface-400">
              {{ $t('blog.post.publishedAt') }}: {{ new Date(post.publishedAt).toLocaleDateString('ja-JP') }}
            </p>
          </div>

          <!-- 操作ボタン -->
          <div class="flex flex-shrink-0 items-center gap-1">
            <Button
              v-if="post.status !== 'ARCHIVED'"
              :icon="post.status === 'PUBLISHED' ? 'pi pi-eye-slash' : 'pi pi-eye'"
              :loading="publishingId === post.id"
              text
              severity="secondary"
              size="small"
              :title="post.status === 'PUBLISHED' ? '下書きに戻す' : '公開する'"
              @click="handleTogglePublish(post)"
            />
            <NuxtLink :to="`/blog/posts/${post.id}/edit`">
              <Button icon="pi pi-pencil" text severity="secondary" size="small" title="編集" />
            </NuxtLink>
            <Button
              icon="pi pi-trash"
              text
              severity="danger"
              size="small"
              :loading="deletingId === post.id"
              title="削除"
              @click="handleDelete(post.id)"
            />
          </div>
        </div>
      </div>
    </template>

    <!-- 空状態 -->
    <div v-else class="py-16 text-center">
      <DashboardEmptyState
        icon="pi pi-book"
        :message="$t('blog.post.noPost')"
      />
      <Button
        class="mt-4"
        :label="$t('blog.post.createFirst')"
        icon="pi pi-plus"
        @click="showCreateDialog = true"
      />
    </div>

    <!-- 新規作成ダイアログ -->
    <BlogCreateDialog v-model:visible="showCreateDialog" />
  </div>
</template>
