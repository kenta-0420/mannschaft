<script setup lang="ts">
/**
 * F01.5 管理者フィード投稿一覧コンポーネント。
 *
 * Phase 2: GET /api/v1/teams/{id}/friend-feed を呼び出し、
 * フレンドチームからの投稿一覧を表示する。
 * 転送成功時は markAsForwarded を呼び出してカードを更新する。
 */
import type { FriendFeedPost } from '~/types/friendFeed'

const { t } = useI18n()

const props = defineProps<{
  teamId: number
}>()

const emit = defineEmits<{
  forward: [postId: number]
}>()

const { posts, loading, error, fetchFeed, markAsForwarded } = useFriendFeed(props.teamId)

onMounted(() => {
  fetchFeed()
})

/** API型 → カード型のマッピング */
function toCardPost(p: FriendFeedPost) {
  return {
    id: p.postId,
    sourceTeamName: p.sourceTeam.name,
    content: p.content,
    createdAt: p.receivedAt,
    isForwarded: p.forwardStatus.isForwarded,
  }
}

function onForward(postId: number) {
  emit('forward', postId)
}

/** 親コンポーネントから呼び出せるよう公開する */
defineExpose({ markAsForwarded })
</script>

<template>
  <div>
    <!-- ローディング中 -->
    <div v-if="loading" class="flex justify-center py-8">
      <i class="pi pi-spin pi-spinner text-2xl text-primary" />
    </div>

    <!-- エラー -->
    <div v-else-if="error" class="py-4 text-center text-red-500">
      <i class="pi pi-exclamation-triangle mr-2" />
      {{ error }}
    </div>

    <!-- 投稿なし -->
    <DashboardEmptyState
      v-else-if="posts.length === 0"
      icon="pi pi-inbox"
      :message="t('friend_feed.list.empty')"
    />

    <!-- 投稿カード一覧 -->
    <div v-else class="flex flex-col gap-4">
      <FriendsFriendFeedPostCard
        v-for="post in posts"
        :key="post.postId"
        :post="toCardPost(post)"
        @forward="onForward"
      />
    </div>
  </div>
</template>
