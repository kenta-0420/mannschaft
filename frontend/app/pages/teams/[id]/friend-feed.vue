<script setup lang="ts">
/**
 * F01.5 管理者フィードページ。
 *
 * フレンドチームから配信された投稿を管理者が確認し、
 * 自チーム内タイムラインへ転送する操作を行う。
 *
 * 権限: ADMIN or MANAGE_FRIEND_TEAMS 保持 DEPUTY_ADMIN
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = Number(route.params.id)

const { isAdmin, can, loadPermissions } = useRoleAccess('team', teamId)

const loading = ref(true)
const permissionDenied = ref(false)

/** 転送モーダル制御 */
const forwardModalVisible = ref(false)
const forwardPostId = ref<number | null>(null)
const forwardSourceTeamName = ref('')

/** FriendFeedPostList への ref（markAsForwarded 呼び出し用） */
const feedListRef = ref<{ markAsForwarded: (postId: number, forwardId: number) => void } | null>(null)

/** 権限チェック */
onMounted(async () => {
  try {
    await loadPermissions()
    // ADMIN or MANAGE_FRIEND_TEAMS 保持者のみアクセス可
    if (!isAdmin.value && !can('MANAGE_FRIEND_TEAMS')) {
      permissionDenied.value = true
    }
  }
  finally {
    loading.value = false
  }
})

/** 転送ボタン押下 → モーダル表示 */
function onForward(postId: number) {
  forwardPostId.value = postId
  forwardSourceTeamName.value = ''
  forwardModalVisible.value = true
}

/** 転送成功時 — 該当カードを転送済みに更新する */
function onForwardSuccess(forwardId: number) {
  if (forwardPostId.value !== null) {
    feedListRef.value?.markAsForwarded(forwardPostId.value, forwardId)
  }
  forwardModalVisible.value = false
}
</script>

<template>
  <PageLoading v-if="loading" />

  <!-- 権限不足 -->
  <div v-else-if="permissionDenied" class="flex flex-col items-center justify-center py-16">
    <i class="pi pi-lock mb-4 text-4xl text-surface-400" />
    <p class="text-surface-500">{{ t('friend_feed.permission_denied') }}</p>
    <BackButton class="mt-4" />
  </div>

  <!-- メインコンテンツ -->
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="t('friend_feed.title')">
        <span class="text-sm text-surface-400">{{ t('friend_feed.subtitle') }}</span>
      </PageHeader>
    </div>

    <div class="mx-auto max-w-2xl">
      <!-- 投稿一覧 -->
      <FriendsFriendFeedPostList
        ref="feedListRef"
        :team-id="teamId"
        @forward="onForward"
      />
    </div>

    <!-- 転送モーダル -->
    <FriendsFriendForwardModal
      v-model="forwardModalVisible"
      :team-id="teamId"
      :post-id="forwardPostId"
      :source-team-name="forwardSourceTeamName"
      @success="onForwardSuccess"
    />
  </div>
</template>
