<script setup lang="ts">
import type { TeamFriendView } from '~/types/social-friend'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const friendTeamsApi = useFriendTeamsApi()
const notification = useNotification()
const { isAdmin, can, loadPermissions, loading: roleLoading } = useRoleAccess('team', teamId)

const canManageFriends = computed(() => isAdmin.value || can('MANAGE_FRIEND_TEAMS'))

// ─────────────────────────────────────────
// フレンドチーム一覧
// ─────────────────────────────────────────
const friends = ref<TeamFriendView[]>([])
const listLoading = ref(false)
const hasNext = ref(false)
const currentPage = ref(0)
const PAGE_SIZE = 20

async function loadFriends(page = 0) {
  listLoading.value = true
  try {
    const res = await friendTeamsApi.listFriends(teamId.value, { page, size: PAGE_SIZE })
    if (page === 0) {
      friends.value = res.data
    }
    else {
      friends.value.push(...res.data)
    }
    hasNext.value = res.pagination.hasNext
    currentPage.value = page
  }
  catch (error) {
    friendTeamsApi.handleApiError(error, 'フレンドチーム一覧取得')
  }
  finally {
    listLoading.value = false
  }
}

async function loadMore() {
  await loadFriends(currentPage.value + 1)
}

// ─────────────────────────────────────────
// フォロー（新規フレンド追加）
// ─────────────────────────────────────────
const showFollowDialog = ref(false)
const followTargetId = ref<number | null>(null)
const followSubmitting = ref(false)

function openFollowDialog() {
  followTargetId.value = null
  showFollowDialog.value = true
}

async function submitFollow() {
  if (!followTargetId.value) return
  followSubmitting.value = true
  try {
    await friendTeamsApi.follow(teamId.value, { targetTeamId: followTargetId.value })
    notification.success(t('team.friends.followSuccess'))
    showFollowDialog.value = false
    await loadFriends(0)
  }
  catch (error) {
    friendTeamsApi.handleApiError(error, 'フォロー')
  }
  finally {
    followSubmitting.value = false
  }
}

// ─────────────────────────────────────────
// アンフォロー（フォロー解除）
// ─────────────────────────────────────────
const showUnfollowDialog = ref(false)
const unfollowTarget = ref<TeamFriendView | null>(null)
const unfollowSubmitting = ref(false)

function openUnfollowDialog(friend: TeamFriendView) {
  unfollowTarget.value = friend
  showUnfollowDialog.value = true
}

async function submitUnfollow() {
  if (!unfollowTarget.value) return
  unfollowSubmitting.value = true
  try {
    await friendTeamsApi.unfollow(teamId.value, unfollowTarget.value.friendTeamId, {
      pastForwardHandling: 'KEEP',
    })
    notification.success(t('team.friends.unfollowSuccess'))
    showUnfollowDialog.value = false
    unfollowTarget.value = null
    await loadFriends(0)
  }
  catch (error) {
    friendTeamsApi.handleApiError(error, 'フォロー解除')
  }
  finally {
    unfollowSubmitting.value = false
  }
}

// ─────────────────────────────────────────
// 公開/非公開切り替え
// ─────────────────────────────────────────
const visibilityLoading = ref<Record<number, boolean>>({})

async function toggleVisibility(friend: TeamFriendView) {
  visibilityLoading.value[friend.teamFriendId] = true
  try {
    await friendTeamsApi.setVisibility(teamId.value, friend.teamFriendId, {
      isPublic: !friend.isPublic,
    })
    friend.isPublic = !friend.isPublic
    notification.success(
      t(friend.isPublic ? 'team.friends.setPublicSuccess' : 'team.friends.setPrivateSuccess'),
    )
  }
  catch (error) {
    friendTeamsApi.handleApiError(error, '公開設定変更')
  }
  finally {
    visibilityLoading.value[friend.teamFriendId] = false
  }
}

// ─────────────────────────────────────────
// 初期化
// ─────────────────────────────────────────
onMounted(async () => {
  await loadPermissions()
  if (canManageFriends.value) {
    await loadFriends(0)
  }
})

// 権限ロード後にcanManageFriendsがtrueになった場合にデータを取得
watch(canManageFriends, (val) => {
  if (val && friends.value.length === 0 && !listLoading.value) {
    loadFriends(0)
  }
})
</script>

<template>
  <div>
    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="t('team.friends.title')" />
      <Button
        v-if="canManageFriends"
        :label="t('team.friends.follow')"
        icon="pi pi-plus"
        @click="openFollowDialog"
      />
    </div>

    <!-- 権限なし -->
    <div v-if="!roleLoading && !canManageFriends" class="py-16 text-center text-surface-500">
      <i class="pi pi-lock mb-4 text-4xl" />
      <p>{{ t('error.COMMON_002') }}</p>
    </div>

    <!-- ローディング -->
    <div v-else-if="roleLoading || (listLoading && friends.length === 0)" class="flex justify-center py-12">
      <ProgressSpinner style="width: 48px; height: 48px" />
    </div>

    <!-- フレンドチーム一覧 -->
    <template v-else-if="canManageFriends">
      <!-- 空状態 -->
      <div v-if="friends.length === 0 && !listLoading" class="py-16 text-center text-surface-500">
        <i class="pi pi-users mb-4 text-4xl" />
        <p>{{ t('team.friends.empty') }}</p>
      </div>

      <!-- カード一覧 -->
      <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Card
          v-for="friend in friends"
          :key="friend.teamFriendId"
          class="relative"
        >
          <template #title>
            <span class="text-base font-semibold">{{ friend.friendTeamName }}</span>
          </template>
          <template #subtitle>
            <div class="flex flex-wrap gap-2">
              <Tag
                v-if="friend.isPublic"
                :value="t('team.friends.public')"
                severity="success"
                icon="pi pi-eye"
              />
              <Tag
                v-else
                :value="t('team.friends.private')"
                severity="secondary"
                icon="pi pi-eye-slash"
              />
            </div>
          </template>
          <template #content>
            <p class="text-sm text-surface-500">
              フォロー開始: {{ new Date(friend.establishedAt).toLocaleDateString('ja-JP') }}
            </p>
          </template>
          <template #footer>
            <div class="flex items-center justify-between gap-2">
              <!-- 公開/非公開トグル -->
              <div class="flex items-center gap-2">
                <ToggleSwitch
                  :model-value="friend.isPublic"
                  :disabled="!!visibilityLoading[friend.teamFriendId]"
                  @update:model-value="toggleVisibility(friend)"
                />
                <span class="text-sm">
                  {{ friend.isPublic ? t('team.friends.public') : t('team.friends.private') }}
                </span>
              </div>
              <!-- アンフォローボタン -->
              <Button
                :label="t('team.friends.unfollow')"
                severity="danger"
                size="small"
                outlined
                @click="openUnfollowDialog(friend)"
              />
            </div>
          </template>
        </Card>
      </div>

      <!-- もっと見る -->
      <div v-if="hasNext" class="mt-6 flex justify-center">
        <Button
          :label="t('button.loadMore')"
          severity="secondary"
          outlined
          :loading="listLoading"
          @click="loadMore"
        />
      </div>
    </template>

    <!-- フォローダイアログ -->
    <Dialog
      v-model:visible="showFollowDialog"
      :header="t('team.friends.follow')"
      :style="{ width: '400px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <label class="flex flex-col gap-1">
          <span class="text-sm font-medium">{{ t('team.friends.targetTeamId') }}</span>
          <InputNumber
            v-model="followTargetId"
            :use-grouping="false"
            :min="1"
            :placeholder="t('team.friends.targetTeamId')"
            class="w-full"
          />
        </label>
      </div>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="showFollowDialog = false"
        />
        <Button
          :label="t('team.friends.follow')"
          :loading="followSubmitting"
          :disabled="!followTargetId"
          @click="submitFollow"
        />
      </template>
    </Dialog>

    <!-- アンフォロー確認ダイアログ -->
    <Dialog
      v-model:visible="showUnfollowDialog"
      :header="t('team.friends.unfollow')"
      :style="{ width: '400px' }"
      modal
    >
      <p>
        <strong>{{ unfollowTarget?.friendTeamName }}</strong>
        {{ t('team.friends.followConfirm') }}
      </p>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="showUnfollowDialog = false"
        />
        <Button
          :label="t('team.friends.unfollow')"
          severity="danger"
          :loading="unfollowSubmitting"
          @click="submitUnfollow"
        />
      </template>
    </Dialog>
  </div>
</template>
