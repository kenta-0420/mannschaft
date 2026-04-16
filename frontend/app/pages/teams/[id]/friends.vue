<script setup lang="ts">
/**
 * F01.5 フレンドチーム一覧画面 — `/teams/{id}/friends`。
 *
 * 権限:
 * - 認証必須（`auth` middleware）。
 * - SUPPORTER を含む全ロールで閲覧可能（バックエンドが is_public=TRUE のみ返す）。
 * - フォロー追加ボタンは ADMIN または DEPUTY_ADMIN（MANAGE_FRIEND_TEAMS 保持）のみ表示。
 * - フォロー解除ボタンは canEdit（ADMIN/DEPUTY_ADMIN）のみ。
 * - 公開設定切替は canToggleVisibility（ADMIN のみ）。
 *
 * SUPPORTER UX:
 * - バックエンドが自動で is_public=TRUE のみ返却するため、フロント側では特に処理不要。
 *   ただし SUPPORTER 向けに「公開されているフレンドのみ表示中」の注意バナーを表示する。
 */
import type { FollowTeamResponse } from '~/types/friends'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const { t } = useI18n()
const { roleName, isAdmin, isAdminOrDeputy, can, loadPermissions } = useRoleAccess('team', teamId)

const dialogOpen = ref(false)
const listRef = ref<{ refresh: () => Promise<void> } | null>(null)

// 「フォロー追加」ボタン表示権限: ADMIN または MANAGE_FRIEND_TEAMS 保持の DEPUTY_ADMIN
const canManageFriends = computed(
  () => isAdmin.value || (isAdminOrDeputy.value && can('MANAGE_FRIEND_TEAMS')),
)

// フォロー解除操作: canManageFriends と同条件
const canEditList = computed(() => canManageFriends.value)

// 公開設定切替: ADMIN のみ（設計書 §3）
const canToggleVisibility = computed(() => isAdmin.value)

// SUPPORTER 向けバナー表示
const isSupporterView = computed(() => roleName.value === 'SUPPORTER')

function openFollowDialog() {
  dialogOpen.value = true
}

async function onFollowSuccess(_response: FollowTeamResponse) {
  // 相互フォロー成立時のみ一覧に追加される。片方向フォローでも一覧を再取得して状態を揃える。
  await listRef.value?.refresh()
}

onMounted(() => {
  void loadPermissions()
})
</script>

<template>
  <div>
    <div class="mb-6 flex items-start justify-between gap-3">
      <div class="flex items-start gap-3">
        <BackButton />
        <div>
          <PageHeader :title="t('friends.title')" />
          <p class="text-sm text-surface-500">{{ t('friends.subtitle') }}</p>
        </div>
      </div>
      <Button
        v-if="canManageFriends"
        :label="t('friends.actions.follow')"
        icon="pi pi-user-plus"
        @click="openFollowDialog"
      />
    </div>

    <div
      v-if="isSupporterView"
      class="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-200"
    >
      <i class="pi pi-info-circle mr-2" />
      {{ t('friends.list.supporter_notice') }}
    </div>

    <TeamFriendList
      ref="listRef"
      :team-id="teamId"
      :can-edit="canEditList"
      :can-toggle-visibility="canToggleVisibility"
    />

    <FriendFollowDialog
      v-model="dialogOpen"
      :team-id="teamId"
      @success="onFollowSuccess"
    />
  </div>
</template>
