<script setup lang="ts">
/**
 * チーム詳細「フレンドチーム」タブ（永続シェル配下の子ルート・所属者のみ）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 本文上部のツールバーに「使い方」（所属者全員）と「フォロー」（ADMIN、または
 * MANAGE_FRIEND_TEAMS を持つ DEPUTY_ADMIN）を置く。
 * 管理者にはフォルダ/フィード/転送履歴へのショートカットを表示する。
 * 直リンク防御: 未所属（roleName null）と GUEST（user_roles のみで在籍なし。BE の resolveEffectiveRole は
 * GUEST を非 null で返すため単純な falsy ガードを素通りする）には権限なし案内を出す。
 * 加えて一覧取得の 403 も TeamFriendList が権限なし案内に倒す（二重防御）。
 *
 * SUPPORTER にはバックエンドが is_public=TRUE のみ返すため、その旨の注意バナーを出す。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'
import type { FollowTeamResponse } from '~/types/friends'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdmin, isAdminOrDeputy, roleName } = useTeamShellContext()
// シェルコンテキストは個別権限（MANAGE_FRIEND_TEAMS）を持たないため、判定のみ useRoleAccess で補う
const { can, loadPermissions } = useRoleAccess('team', teamSlug)

const dialogOpen = ref(false)
const showGuide = ref(false)
const listRef = ref<{ refresh: () => Promise<void> } | null>(null)

// 「フォロー」ボタン表示権限: ADMIN または MANAGE_FRIEND_TEAMS 保持の DEPUTY_ADMIN
const canManageFriends = computed(
  () => isAdmin.value || (isAdminOrDeputy.value && can('MANAGE_FRIEND_TEAMS')),
)
// 本文を見せてよいのは在籍ロール（GUEST は在籍なしのため除外）
const canViewTab = computed(() => !!roleName.value && roleName.value !== 'GUEST')
const isGuestView = computed(() => roleName.value === 'GUEST')
const isSupporterView = computed(() => roleName.value === 'SUPPORTER')

async function onFollowSuccess(_response: FollowTeamResponse) {
  // 片方向フォローでも一覧を再取得して状態を揃える。
  await listRef.value?.refresh()
}

onMounted(() => {
  void loadPermissions()
})
</script>

<template>
  <div class="mt-4">
    <template v-if="canViewTab">
      <div data-testid="friend-toolbar" class="mb-4 flex flex-wrap items-center justify-end gap-2">
        <Button
          data-testid="friend-guide-button"
          :label="$t('button.help')"
          icon="pi pi-question-circle"
          class="shrink-0 whitespace-nowrap"
          severity="secondary"
          outlined
          @click="showGuide = true"
        />
        <Button
          v-if="canManageFriends"
          data-testid="friend-follow-button"
          :label="$t('friends.actions.follow')"
          icon="pi pi-user-plus"
          class="shrink-0 whitespace-nowrap"
          @click="dialogOpen = true"
        />
      </div>

      <div
        v-if="isSupporterView"
        data-testid="friend-supporter-notice"
        class="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-200"
      >
        <i class="pi pi-info-circle mr-2" />
        {{ $t('friends.list.supporter_notice') }}
      </div>

      <!-- 管理者向けショートカット -->
      <div
        v-if="isAdmin"
        data-testid="friend-admin-shortcuts"
        class="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2"
      >
        <NuxtLink class="block min-w-0" :to="`/teams/${teamSlug}/friend-folders`">
          <Button data-testid="friend-shortcut-folders" :label="$t('folders.title')" icon="pi pi-folder-open" class="w-full !h-auto !whitespace-normal py-2" outlined />
        </NuxtLink>
        <NuxtLink class="block min-w-0" :to="`/teams/${teamSlug}/friend-feed`">
          <Button data-testid="friend-shortcut-feed" :label="$t('friend_feed.title')" icon="pi pi-inbox" class="w-full !h-auto !whitespace-normal py-2" outlined />
        </NuxtLink>
        <NuxtLink class="block min-w-0" :to="`/teams/${teamSlug}/friend-forward-exports`">
          <Button data-testid="friend-shortcut-forward-exports" :label="$t('forward_exports.title')" icon="pi pi-history" class="w-full !h-auto !whitespace-normal py-2" outlined />
        </NuxtLink>
      </div>
      <TeamFriendList
        ref="listRef"
        :team-id="teamSlug"
        :can-edit="canManageFriends"
        :can-toggle-visibility="isAdmin"
      />

      <FriendFollowDialog
        v-model="dialogOpen"
        :team-id="teamSlug"
        @success="onFollowSuccess"
      />
      <FriendTeamGuideModal v-model:visible="showGuide" />
    </template>
    <DashboardErrorState
      v-else-if="isGuestView"
      testid="friend-forbidden"
      kind="forbidden"
    />
  </div>
</template>
