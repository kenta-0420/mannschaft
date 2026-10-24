<script setup lang="ts">
/**
 * チーム詳細「フレンドチーム」タブ（永続シェル配下の子ルート・所属者のみ）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 本文上部のツールバーに「使い方」（所属者全員）と「フォロー」（
 * MANAGE_FRIEND_TEAMS の実効権限を持つ利用者）を置く。
 * 管理者にはフォルダ/フィード/転送履歴へのショートカットを表示する。
 * 直リンク防御: ロール解決済みで未所属（roleName null。読込中・取得失敗は別扱い）と GUEST（user_roles のみで在籍なし。BE の resolveEffectiveRole は
 * GUEST を非 null で返すため単純な falsy ガードを素通りする）には権限なし案内を出す。
 * 加えて一覧取得の 403 も TeamFriendList が権限なし案内に倒す（二重防御）。
 *
 * SUPPORTER にはバックエンドが is_public=TRUE のみ返すため、その旨の注意バナーを出す。
 */
import type { FriendRequestSource, FriendRequestTarget } from '~/types/friends'
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { team, isAdmin, roleName, roleResolved, roleLoadFailed, refresh } = useTeamShellContext()
// シェルコンテキストは個別権限（MANAGE_FRIEND_TEAMS）を持たないため、判定のみ useRoleAccess で補う
const { can, loadPermissions } = useRoleAccess('team', teamSlug)

const sourceTeam = computed<FriendRequestSource | undefined>(() => team.value?.numericId ? { teamId: team.value.numericId, teamSlug: team.value.slug ?? teamSlug.value, teamName: team.value.basicInfo?.name ?? teamSlug.value } : undefined)
const incomingListRef = ref<{ refresh: () => Promise<void> } | null>(null)
const selectedRequestTarget = ref<FriendRequestTarget | undefined>()
const activeList = ref('friends')
const dialogOpen = ref(false)
const showGuide = ref(false)
const listRef = ref<{ refresh: () => Promise<void> } | null>(null)
const pendingListRef = ref<{ refresh: () => Promise<void> } | null>(null)
const initialFollowSlug = ref('')

// フォロー・フォロー中一覧はBEと同じ実効権限だけで表示する。
const canManageFriends = computed(
  () => can('MANAGE_FRIEND_TEAMS'),
)
// 本文を見せてよいのは在籍ロール（GUEST は在籍なしのため除外）
const canViewTab = computed(() => !!roleName.value && roleName.value !== 'GUEST')
// 権限なし案内: GUEST、または「ロール解決済みで取得成功なのに roleName=null」（公開チームの未所属・他テナント）。
// 読込中（roleResolved=false）と取得失敗は別扱い（ちらつき防止・失敗を権限なしに倒さない）。
const isForbiddenView = computed(
  () => roleName.value === 'GUEST'
    || (roleResolved.value && !roleLoadFailed.value && !roleName.value),
)
const isRoleLoadError = computed(() => roleResolved.value && roleLoadFailed.value && !roleName.value)
const isSupporterView = computed(() => roleName.value === 'SUPPORTER')

async function refreshLists() {
  // 片方向フォローでも一覧を再取得して状態を揃える。
  await Promise.all([listRef.value?.refresh(), pendingListRef.value?.refresh(), incomingListRef.value?.refresh()])
}

async function onPendingChanged() {
  // 取消元一覧は自身で取得済みなので、兄弟の成立/受信一覧だけを揃える。
  await Promise.all([listRef.value?.refresh(), incomingListRef.value?.refresh()])
}

function openFollow(slug = '') {
  selectedRequestTarget.value = undefined
  initialFollowSlug.value = slug
  dialogOpen.value = true
}

function respondToRequest(target: FriendRequestTarget) {
  selectedRequestTarget.value = target
  initialFollowSlug.value = target.teamSlug
  dialogOpen.value = true
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
          @click="openFollow()"
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
      <SelectButton v-if="canManageFriends" v-model="activeList" :allow-empty="false" :options="[{ value: 'friends', label: $t('friends.title') }, { value: 'pending', label: $t('friends.pending.title') }, { value: 'incoming', label: $t('friends.request.incoming') }]" option-label="label" option-value="value" class="mb-4 max-w-full overflow-x-auto" data-testid="friend-list-tabs" />
      <TeamFriendIncomingFollowList v-if="canManageFriends && sourceTeam" v-show="activeList === 'incoming'" ref="incomingListRef" :team-id="String(sourceTeam.teamId)" :source-team="sourceTeam" class="mb-6" @respond="respondToRequest" />
      <TeamFriendPendingFollowList
        v-if="canManageFriends"
        v-show="activeList === 'pending'"
        ref="pendingListRef"
        :team-id="teamSlug"
        class="mb-6"
        @refresh="onPendingChanged"
        @refollow="openFollow"
      />
      <TeamFriendList
        v-show="activeList === 'friends'"
        ref="listRef"
        :team-id="teamSlug"
        :can-edit="canManageFriends"
        :can-toggle-visibility="isAdmin"
        @refresh="incomingListRef?.refresh(); pendingListRef?.refresh()"
      />

      <FriendFollowDialog
        v-model="dialogOpen"
        :team-id="teamSlug"
        :source-team="sourceTeam"
        :target-team="selectedRequestTarget"
        :initial-slug="initialFollowSlug"
        @success="refreshLists"
        @already-following="refreshLists"
        @refresh="refreshLists"
      />
      <FriendTeamGuideModal v-model:visible="showGuide" />
    </template>
    <DashboardErrorState
      v-else-if="isForbiddenView"
      testid="friend-forbidden"
      kind="forbidden"
    />
    <DashboardErrorState
      v-else-if="isRoleLoadError"
      testid="friend-role-error"
      @retry="refresh()"
    />
  </div>
</template>
