<script setup lang="ts">
import type { TimelinePostResponse, TimelineScopeType } from '~/types/timeline'

const props = defineProps<{
  scopeType: TimelineScopeType
  scopeId?: number
  canPin?: boolean
  canDeleteOthers?: boolean
}>()

const emit = defineEmits<{
  clickPost: [postId: number]
}>()

const { getFeed, addReaction, removeReaction, addBookmark, removeBookmark, pinPost, deletePost } =
  useTimelineApi()
const { showSuccess, showError } = useNotification()

const pinnedPosts = ref<TimelinePostResponse[]>([])
const posts = ref<TimelinePostResponse[]>([])
const nextCursor = ref<number | null>(null)
const hasNext = ref(false)
const loading = ref(false)
const initialLoaded = ref(false)

async function loadFeed(cursor?: number) {
  loading.value = true
  try {
    const res = await getFeed({
      scopeType: props.scopeType,
      scopeId: props.scopeId,
      cursor,
    })
    if (!cursor) {
      pinnedPosts.value = res.data.pinned
      posts.value = res.data.posts
    } else {
      posts.value.push(...res.data.posts)
    }
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
    initialLoaded.value = true
  } catch {
    showError('タイムラインの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function loadMore() {
  if (nextCursor.value && !loading.value) {
    loadFeed(nextCursor.value)
  }
}

async function onReaction(postId: number, emoji: string) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (!post) return
  try {
    if (post.myReactions.includes(emoji)) {
      await removeReaction(postId, emoji)
      post.myReactions = post.myReactions.filter((e) => e !== emoji)
      post.reactionSummary[emoji] = (post.reactionSummary[emoji] || 1) - 1
      if (post.reactionSummary[emoji] <= 0) {
        post.reactionSummary = Object.fromEntries(
          Object.entries(post.reactionSummary).filter(([k]) => k !== emoji),
        )
      }
      post.reactionCount--
    } else {
      await addReaction(postId, emoji)
      post.myReactions.push(emoji)
      post.reactionSummary[emoji] = (post.reactionSummary[emoji] || 0) + 1
      post.reactionCount++
    }
  } catch {
    showError('リアクションに失敗しました')
  }
}

async function onBookmark(postId: number) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (!post) return
  try {
    if (post.isBookmarked) {
      await removeBookmark(postId)
      post.isBookmarked = false
    } else {
      await addBookmark(postId)
      post.isBookmarked = true
    }
  } catch {
    showError('ブックマークに失敗しました')
  }
}

async function onPin(postId: number) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (!post) return
  try {
    await pinPost(postId)
    showSuccess(post.isPinned ? 'ピン解除しました' : 'ピン留めしました')
    refresh()
  } catch {
    showError('ピン操作に失敗しました')
  }
}

async function onDelete(postId: number) {
  try {
    await deletePost(postId)
    posts.value = posts.value.filter((p) => p.id !== postId)
    pinnedPosts.value = pinnedPosts.value.filter((p) => p.id !== postId)
    showSuccess('投稿を削除しました')
  } catch {
    showError('削除に失敗しました')
  }
}

function refresh() {
  loadFeed()
}

onMounted(() => loadFeed())

defineExpose({ refresh })
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- ピン留め投稿 -->
    <TimelinePostCard
      v-for="post in pinnedPosts"
      :key="`pin-${post.id}`"
      :post="post"
      :can-pin="canPin"
      :can-delete-others="canDeleteOthers"
      @reaction="onReaction"
      @bookmark="onBookmark"
      @pin="onPin"
      @delete="onDelete"
      @click-post="(id) => emit('clickPost', id)"
    />

    <!-- 通常投稿 -->
    <TimelinePostCard
      v-for="post in posts"
      :key="post.id"
      :post="post"
      :can-pin="canPin"
      :can-delete-others="canDeleteOthers"
      @reaction="onReaction"
      @bookmark="onBookmark"
      @pin="onPin"
      @delete="onDelete"
      @click-post="(id) => emit('clickPost', id)"
    />

    <!-- 空状態 -->
    <div
      v-if="initialLoaded && posts.length === 0 && pinnedPosts.length === 0"
      class="py-12 text-center"
    >
      <i class="pi pi-comments mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">まだ投稿がありません</p>
    </div>

    <!-- もっと読む -->
    <div v-if="hasNext" class="flex justify-center py-4">
      <Button label="もっと読む" text :loading="loading" @click="loadMore" />
    </div>

    <!-- ローディング -->
    <div v-if="loading && !initialLoaded" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>
  </div>
</template>
