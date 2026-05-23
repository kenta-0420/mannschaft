<script setup lang="ts">
import type { PublicPostComment, SpringPage } from '~/types/public'

/**
 * F19.1 Phase 6-B: 公開投稿コメントセクション。
 *
 * - コメント一覧の読み込み
 * - ログイン済みなら PublicPostCommentForm を表示
 * - 未ログインなら「ログインしてコメント」CTA を表示
 * - コメント一覧（PublicPostCommentItem のリスト）
 * - ページネーション
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6-B
 */
const props = defineProps<{
  postId: number | string
}>()

const { t } = useI18n()
const { fetchPostComments, deleteComment } = usePublicApi()
const authStore = useAuthStore()

const isAuthenticated = computed(() => authStore.isAuthenticated)
const currentUserId = computed(() => authStore.currentUser?.id)
const isAdmin = computed(
  () =>
    authStore.currentUser?.systemRole === 'SYSTEM_ADMIN' ||
    authStore.currentUser?.systemRole === 'ADMIN',
)

const page = ref(0)
const PAGE_SIZE = 20

const comments = ref<PublicPostComment[]>([])
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)

async function loadComments(targetPage = 0) {
  loading.value = true
  try {
    const result: SpringPage<PublicPostComment> = await fetchPostComments(
      props.postId,
      targetPage,
      PAGE_SIZE,
    )
    comments.value = result.content
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
    page.value = targetPage
  } finally {
    loading.value = false
  }
}

async function handleDelete(commentId: string) {
  await deleteComment(props.postId, commentId)
  await loadComments(page.value)
}

async function handleSubmitted() {
  // 新規投稿後は最終ページへ移動して最新コメントを表示
  const lastPage = Math.max(0, totalPages.value - 1)
  await loadComments(lastPage)
  // コメント件数が増えた場合は最終ページを再計算
  const newLastPage = Math.max(0, totalPages.value - 1)
  if (newLastPage !== lastPage) {
    await loadComments(newLastPage)
  }
}

onMounted(() => {
  loadComments(0)
})
</script>

<template>
  <section
    class="mt-8 space-y-4"
    data-testid="comment-section"
  >
    <h2 class="text-xl font-bold text-surface-900 dark:text-surface-50">
      {{ t('public.comments.title') }}
      <span
        v-if="totalElements > 0"
        class="ml-2 text-base font-normal text-surface-500"
      >
        ({{ totalElements }})
      </span>
    </h2>

    <!-- ログイン済み: コメントフォームを表示 -->
    <PublicPostCommentForm
      v-if="isAuthenticated"
      :post-id="postId"
      @submitted="handleSubmitted"
    />

    <!-- 未ログイン: ログイン誘導 CTA -->
    <div
      v-else
      class="rounded-lg border border-surface-200 bg-surface-50 p-4 text-sm dark:border-surface-700 dark:bg-surface-900"
    >
      <span class="text-surface-600 dark:text-surface-400">
        {{ t('public.comments.loginToComment') }}
      </span>
      <NuxtLink to="/login" class="ml-1 text-primary underline hover:no-underline">
        {{ t('public.comments.loginLink') }}
      </NuxtLink>
    </div>

    <!-- 読み込み中 -->
    <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
      {{ t('public.posts.loading') }}
    </div>

    <!-- コメント一覧 -->
    <template v-else>
      <div v-if="comments.length === 0" class="py-4 text-center text-sm text-surface-400">
        {{ t('public.comments.empty') }}
      </div>
      <div v-else class="divide-y divide-surface-100 dark:divide-surface-800">
        <PublicPostCommentItem
          v-for="comment in comments"
          :key="comment.commentId"
          :comment="comment"
          :current-user-id="currentUserId"
          :is-admin="isAdmin"
          @delete="handleDelete"
        />
      </div>

      <!-- ページネーション -->
      <div
        v-if="totalPages > 1"
        class="flex items-center justify-between pt-2"
      >
        <Button
          :label="t('public.posts.prev')"
          severity="secondary"
          outlined
          :disabled="page === 0"
          @click="loadComments(page - 1)"
        />
        <span class="text-sm text-surface-500">
          {{ t('public.posts.page', { page: page + 1, total: totalPages }) }}
        </span>
        <Button
          :label="t('public.posts.next')"
          severity="secondary"
          outlined
          :disabled="page >= totalPages - 1"
          @click="loadComments(page + 1)"
        />
      </div>
    </template>
  </section>
</template>
