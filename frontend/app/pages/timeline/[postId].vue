<script setup lang="ts">
import type { TimelinePostResponse } from '~/types/timeline'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const router = useRouter()
const postId = Number(route.params.postId)

const {
  getPost,
  createReply,
  getReplies,
  addBookmark,
  removeBookmark,
} = useTimelineApi()
const { t } = useI18n()
const { showSuccess, showError } = useNotification()

const post = ref<(TimelinePostResponse & { recentReplies: TimelinePostResponse[] }) | null>(null)
const replies = ref<TimelinePostResponse[]>([])
const replyContent = ref('')
const submittingReply = ref(false)
const loadingMore = ref(false)
const replyCursor = ref<number | null>(null)
const hasMoreReplies = ref(false)
// 取得状態: loading / error(不在・権限なしを区別しない) / loaded(post が非 null)
const loadingPost = ref(true)
const loadFailed = ref(false)

async function loadPost() {
  loadingPost.value = true
  loadFailed.value = false
  try {
    const res = await getPost(postId)
    post.value = res.data
    const recent = res.data.recentReplies || []
    replies.value = recent
    // recentReplies は会話の古い順・先頭最大 N 件のプレビュー。返信総数がプレビュー件数を超えるなら続きがある。
    // 返信一覧APIのカーソルは「その ID より後（新しい）」を返すため、末尾（=最新 ID）を起点にする。
    const total = res.data.stats?.replyCount ?? recent.length
    hasMoreReplies.value = total > recent.length
    replyCursor.value = recent.length > 0 ? recent[recent.length - 1]!.id : null
  } catch {
    // 不在(404)と権限なしを区別せず同じ表示にする（存在を漏らさない）
    post.value = null
    loadFailed.value = true
    showError(t('timeline.detail.loadFailed'))
  } finally {
    loadingPost.value = false
  }
}

async function loadMoreReplies() {
  if (!replyCursor.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const res = await getReplies(postId, replyCursor.value)
    replies.value.push(...res.data.posts)
    replyCursor.value = res.meta.nextCursor
    hasMoreReplies.value = res.meta.hasNext
  } catch {
    showError(t('timeline.detail.repliesLoadFailed'))
  } finally {
    loadingMore.value = false
  }
}

async function onReply() {
  if (!replyContent.value.trim() || submittingReply.value) return
  submittingReply.value = true
  try {
    const res = await createReply(postId, replyContent.value.trim())
    replies.value.unshift(res.data)
    if (post.value?.stats) post.value.stats.replyCount++
    replyContent.value = ''
    showSuccess(t('timeline.detail.replySuccess'))
  } catch {
    showError(t('timeline.detail.replyFailed'))
  } finally {
    submittingReply.value = false
  }
}

function onMitayoToggled(targetId: number, mitayo: boolean, mitayoCount: number) {
  const target = targetId === postId ? post.value : replies.value.find((r) => r.id === targetId)
  if (!target) return
  target.mitayo = mitayo
  target.mitayoCount = mitayoCount
  if (target.stats) target.stats.reactionCount = mitayoCount
}

async function onBookmark(targetId: number) {
  const target = targetId === postId ? post.value : replies.value.find((r) => r.id === targetId)
  if (!target) return
  try {
    if (target.isBookmarked) {
      await removeBookmark(targetId)
      target.isBookmarked = false
    } else {
      await addBookmark(targetId)
      target.isBookmarked = true
    }
  } catch {
    showError(t('timeline.detail.bookmarkFailed'))
  }
}

function goBack() {
  router.back()
}

onMounted(() => loadPost())
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <!-- 戻るボタン -->
    <Button icon="pi pi-arrow-left" :label="t('timeline.detail.back')" text size="small" class="mb-4" @click="goBack" />

    <div v-if="post">
      <!-- メイン投稿 -->
      <TimelinePostCard
        :post="post"
        :replies-accordion="false"
        @mitayo-toggled="onMitayoToggled"
        @bookmark="onBookmark"
        @click-post="() => {}"
      />

      <!-- リプライフォーム -->
      <div class="mt-4 rounded-xl border border-surface-300 bg-surface-0 p-4">
        <Textarea
          v-model="replyContent"
          :placeholder="t('timeline.detail.replyPlaceholder')"
          auto-resize
          rows="2"
          class="mb-2 w-full"
        />
        <div class="flex justify-end">
          <Button
            :label="t('timeline.detail.replySubmit')"
            size="small"
            :loading="submittingReply"
            :disabled="!replyContent.trim()"
            @click="onReply"
          />
        </div>
      </div>

      <!-- リプライ一覧 -->
      <div class="mt-4 flex flex-col gap-3">
        <p v-if="replies.length > 0" class="text-sm font-medium text-surface-500">
          {{ t('timeline.detail.replyCount', { count: post.stats?.replyCount ?? 0 }) }}
        </p>
        <TimelinePostCard
          v-for="reply in replies"
          :key="reply.id"
          :post="reply"
          :replies-accordion="false"
          @mitayo-toggled="onMitayoToggled"
          @bookmark="onBookmark"
          @click-post="(id) => router.push(`/timeline/${id}`)"
        />
        <Button
          v-if="hasMoreReplies"
          :label="t('timeline.detail.loadMoreReplies')"
          text
          :loading="loadingMore"
          @click="loadMoreReplies"
        />
      </div>
    </div>

    <!-- 取得失敗（不在・権限なし共通） -->
    <div
      v-else-if="loadFailed"
      data-testid="timeline-post-not-found"
      class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
    >
      {{ t('timeline.detail.notFound') }}
    </div>

    <!-- ローディング -->
    <PageLoading v-else-if="loadingPost" size="40px" />
  </div>
</template>
