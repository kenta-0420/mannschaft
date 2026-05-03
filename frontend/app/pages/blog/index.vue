<script setup lang="ts">
import type { BlogPostResponse, BlogPostStatus } from '~/types/cms'

definePageMeta({ middleware: 'auth' })

const { getMyPosts, deleteMyPost, publishMyPost } = useBlogApi()
const { handleError } = useErrorHandler()
const { success } = useNotification()

const myPosts = ref<BlogPostResponse[]>([])
const loadingMine = ref(false)
const showCreateDialog = ref(false)
const deletingId = ref<number | null>(null)
const publishingId = ref<number | null>(null)

const latestMyPosts = computed<BlogPostResponse[]>(() => myPosts.value.slice(0, 5))

async function loadMyPosts() {
  loadingMine.value = true
  try {
    const res = await getMyPosts({ page: 0, size: 20 })
    myPosts.value = res.data
  } catch (error) {
    handleError(error)
  } finally {
    loadingMine.value = false
  }
}

async function handleDelete(postId: number) {
  deletingId.value = postId
  try {
    await deleteMyPost(postId)
    myPosts.value = myPosts.value.filter((p) => p.id !== postId)
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
    const idx = myPosts.value.findIndex((p) => p.id === post.id)
    if (idx >= 0 && myPosts.value[idx]) {
      myPosts.value[idx] = { ...myPosts.value[idx]!, status: newStatus as BlogPostStatus }
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

onMounted(() => loadMyPosts())
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 py-8">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="$t('blog.post.myBlog')" />
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
          :label="$t('blog.post.createNew')"
          @click="showCreateDialog = true"
        />
      </div>
    </div>

    <!-- 上段: 自分の投稿（横スクロール） -->
    <section class="mb-8">
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-base font-semibold text-surface-800 dark:text-surface-100">
          {{ $t('blog.post.myPosts') }}
        </h2>
        <span v-if="myPosts.length > 0" class="text-xs text-surface-400">
          {{ $t('blog.post.latestCount', { count: latestMyPosts.length, total: myPosts.length }) }}
        </span>
      </div>

      <PageLoading v-if="loadingMine" />

      <div v-else-if="latestMyPosts.length > 0" class="flex gap-3 overflow-x-auto pb-2">
        <div
          v-for="post in latestMyPosts"
          :key="post.id"
          class="flex w-60 shrink-0 flex-col gap-2 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-900"
        >
          <!-- サムネイル -->
          <div class="relative">
            <img
              v-if="post.coverImageUrl"
              :src="post.coverImageUrl"
              :alt="post.title"
              class="h-28 w-full rounded-lg object-cover"
            >
            <div
              v-else
              class="flex h-28 w-full items-center justify-center rounded-lg bg-surface-100 dark:bg-surface-800"
            >
              <i class="pi pi-book text-3xl text-surface-300" />
            </div>
            <span
              :class="getStatusClass(post.status)"
              class="absolute top-1.5 left-1.5 rounded px-1.5 py-0.5 text-[10px] font-medium"
            >
              {{ getStatusLabel(post.status) }}
            </span>
          </div>

          <!-- 記事情報 -->
          <h3 class="line-clamp-2 text-sm font-semibold text-surface-800 dark:text-surface-100">
            {{ post.title }}
          </h3>
          <p v-if="post.publishedAt" class="text-[10px] text-surface-400">
            {{ new Date(post.publishedAt).toLocaleDateString('ja-JP') }}
          </p>

          <!-- 操作ボタン -->
          <div class="mt-auto flex items-center justify-end gap-0.5 border-t border-surface-100 pt-1.5 dark:border-surface-800">
            <Button
              v-if="post.status !== 'ARCHIVED'"
              :icon="post.status === 'PUBLISHED' ? 'pi pi-eye-slash' : 'pi pi-eye'"
              :loading="publishingId === post.id"
              text
              severity="secondary"
              size="small"
              :title="$t('blog.post.togglePublish')"
              @click="handleTogglePublish(post)"
            />
            <NuxtLink :to="`/blog/posts/${post.id}/edit`">
              <Button
                icon="pi pi-pencil"
                text
                severity="secondary"
                size="small"
                :title="$t('blog.post.editPost')"
              />
            </NuxtLink>
            <Button
              icon="pi pi-trash"
              text
              severity="danger"
              size="small"
              :loading="deletingId === post.id"
              :title="$t('blog.post.deletePost')"
              @click="handleDelete(post.id)"
            />
          </div>
        </div>
      </div>

      <!-- 空状態 -->
      <div
        v-else
        class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-surface-300 bg-surface-50 py-6 dark:border-surface-600 dark:bg-surface-800/50"
      >
        <DashboardEmptyState icon="pi pi-book" :message="$t('blog.post.noPost')" />
        <Button
          :label="$t('blog.post.createFirst')"
          icon="pi pi-plus"
          size="small"
          @click="showCreateDialog = true"
        />
      </div>
    </section>

    <!-- 下段: みんなの投稿 -->
    <section>
      <h2 class="mb-3 text-base font-semibold text-surface-800 dark:text-surface-100">
        {{ $t('blog.post.allPosts') }}
      </h2>
      <BlogPostList />
    </section>

    <!-- 新規作成ダイアログ -->
    <BlogCreateDialog v-model:visible="showCreateDialog" />
  </div>
</template>
