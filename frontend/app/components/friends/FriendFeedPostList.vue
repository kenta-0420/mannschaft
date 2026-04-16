<script setup lang="ts">
/**
 * F01.5 管理者フィード投稿一覧コンポーネント。
 *
 * Phase 1 では投稿取得APIが未実装のため、空リストを表示しつつ
 * 転送ボタンの動作確認が可能なレイアウトを提供する。
 * Phase 2 で GET /api/v1/teams/{id}/friend-feed 連携を追加予定。
 */
const { t } = useI18n()

const props = defineProps<{
  teamId: number
}>()

const emit = defineEmits<{
  forward: [postId: number]
}>()

/**
 * Phase 1: 投稿一覧は空。
 * Phase 2 で useAsyncData + friend-feed API に置換する。
 */
const posts = ref<Array<{
  id: number
  sourceTeamName: string
  content: string
  createdAt: string
  isForwarded?: boolean
}>>([])

function onForward(postId: number) {
  emit('forward', postId)
}
</script>

<template>
  <div>
    <!-- Phase 1: 投稿がない場合 -->
    <DashboardEmptyState
      v-if="posts.length === 0"
      icon="pi pi-inbox"
      :message="t('friend_feed.list.empty')"
    />

    <!-- 投稿カード一覧（Phase 2 でデータが流れる） -->
    <div v-else class="flex flex-col gap-4">
      <FriendsFriendFeedPostCard
        v-for="post in posts"
        :key="post.id"
        :post="post"
        @forward="onForward"
      />
    </div>
  </div>
</template>
