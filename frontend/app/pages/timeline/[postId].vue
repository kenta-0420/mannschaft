<script setup lang="ts">
import type { TimelinePostResponse } from '~/types/timeline'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const router = useRouter()
const postId = Number(route.params.postId)

const { getPost, createReply, getReplies, addReaction, removeReaction, addBookmark, removeBookmark } = useTimelineApi()
const { showSuccess, showError } = useNotification()

const post = ref<(TimelinePostResponse & { recentReplies: TimelinePostResponse[] }) | null>(null)
const replies = ref<TimelinePostResponse[]>([])
const replyContent = ref('')
const submittingReply = ref(false)
const loadingMore = ref(false)
const replyCursor = ref<number | null>(null)
const hasMoreReplies = ref(false)

async function loadPost() {
  try {
    const res = await getPost(postId)
    post.value = res.data
    replies.value = res.data.recentReplies || []
  } catch {
    showError('投稿の取得に失敗しました')
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
    if (post.value) post.value.replyCount++
    replyContent.value = ''
    showSuccess('返信しました')
  } catch {
    showError('返信に失敗しました')
  } finally {
    submittingReply.value = false
  }
}

async function onReaction(targetId: number, emoji: string) {
  const target = targetId === postId
    ? post.value
    : replies.value.find(r => r.id === targetId)
  if (!target) return
  try {
    if (target.myReactions.includes(emoji)) {
      await removeReaction(targetId, emoji)
      target.myReactions = target.myReactions.filter(e => e !== emoji)
      target.reactionSummary[emoji] = (target.reactionSummary[emoji] || 1) - 1
      if (target.reactionSummary[emoji] <= 0) delete target.reactionSummary[emoji]
      target.reactionCount--
    } else {
      await addReaction(targetId, emoji)
      target.myReactions.push(emoji)
      target.reactionSummary[emoji] = (target.reactionSummary[emoji] || 0) + 1
      target.reactionCount++
    }
  } catch {
    showError('リアクションに失敗しました')
  }
}

async function onBookmark(targetId: number) {
  const target = targetId === postId
    ? post.value
    : replies.value.find(r => r.id === targetId)
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
    showError('ブックマークに失敗しました')
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
    <Button
      icon="pi pi-arrow-left"
      label="戻る"
      text
      size="small"
      class="mb-4"
      @click="goBack"
    />

    <div v-if="post">
      <!-- メイン投稿 -->
      <TimelinePostCard
        :post="post"
        @reaction="onReaction"
        @bookmark="onBookmark"
        @click-post="() => {}"
      />

      <!-- リプライフォーム -->
      <div class="mt-4 rounded-xl border border-surface-200 bg-surface-0 p-4">
        <Textarea
          v-model="replyContent"
          placeholder="返信を入力..."
          auto-resize
          rows="2"
          class="mb-2 w-full"
        />
        <div class="flex justify-end">
          <Button
            label="返信"
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
          返信 {{ post.replyCount }}件
        </p>
        <TimelinePostCard
          v-for="reply in replies"
          :key="reply.id"
          :post="reply"
          @reaction="onReaction"
          @bookmark="onBookmark"
          @click-post="(id) => router.push(`/timeline/${id}`)"
        />
        <Button
          v-if="hasMoreReplies"
          label="さらに返信を読み込む"
          text
          :loading="loadingMore"
          @click="loadMoreReplies"
        />
      </div>
    </div>

    <!-- ローディング -->
    <div v-else class="flex justify-center py-12">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>
  </div>
</template>
