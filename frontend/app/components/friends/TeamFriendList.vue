<script setup lang="ts">
/**
 * F01.5 フレンドチーム一覧。
 *
 * 役割:
 * - {@link useFriendTeamsApi.listFriends} でフレンド一覧を取得し、
 *   {@link TeamFriendCard} を map 表示する。
 * - ページネーション UI（PrimeVue Paginator）を提供する。
 * - 空状態は {@link DashboardEmptyState}、取得失敗は {@link DashboardErrorState}（403=権限なし案内、5xx・通信断=再試行付き）。
 * - ローディング中は {@link PageLoading} を使用。
 *
 * Props:
 *   teamId  — 自チーム ID
 *   canEdit — フレンド管理の実効権限を持つか（カードに伝搬）
 *   canToggleVisibility — ADMIN のみ公開設定可能か（カードに伝搬）
 *
 * Emits:
 *   refresh — 取消後の自一覧取得完了を親へ通知（兄弟一覧の再取得に使う）
 */
import type { TeamFriendView } from '~/types/friends'

const props = defineProps<{
  teamId: string
  canEdit: boolean
  canToggleVisibility: boolean
}>()

const emit = defineEmits<{
  refresh: []
}>()

const { t } = useI18n()
const { listFriends, unfollow } = useFriendTeamsApi()
const { confirmAction } = useConfirmDialog()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const friends = ref<TeamFriendView[]>([])
const loading = ref(true)
const { page, rows: pageSize, totalRecords: totalElements, loadWithPageCorrection } = usePagination()
// 取得失敗は空状態と区別して保持する（403=権限なし案内、5xx・通信断=エラー表示＋再試行）
const loadError = shallowRef<unknown>(null)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const response = await loadWithPageCorrection((page, size) => listFriends(props.teamId, { page, size }))
    friends.value = response.data
    totalElements.value = response.pagination.totalElements
  }
  catch (error) {
    friends.value = []
    totalElements.value = 0
    loadError.value = error
  }
  finally {
    loading.value = false
  }
}

async function refresh() {
  await load()
}

async function onPageChange(event: { page: number }) {
  page.value = event.page
  await load()
}

function handleUnfollow(friend: TeamFriendView) {
  confirmAction({
    message: t('friends.messages.unfollow_confirm'),
    header: t('friends.actions.unfollow'),
    onAccept: async () => {
      try {
        // Phase 1 では pastForwardHandling は KEEP 固定（UI での選択は Phase 2/3）
        await unfollow(props.teamId, friend.friendTeamId, {
          pastForwardHandling: 'KEEP',
        })
        notification.success(t('friends.messages.unfollow_success'))
        await refresh()
        emit('refresh')
      }
      catch (error) {
        const apiError = error as { statusCode?: number; data?: { error?: { code?: string } } }
        if (apiError.statusCode === 404 && apiError.data?.error?.code === 'SOCIAL_103') {
          await refresh()
          emit('refresh')
          return
        }
        handleApiError(error)
      }
    },
  })
}

function handleToggleVisibility() {
  // Card 側で楽観更新 + 通知済みなので、ここではカウント再取得のみ行う
  void refresh()
}

// teamId が動的に変わる可能性を考慮
watch(() => props.teamId, () => {
  page.value = 0
  void load()
})

onMounted(() => {
  void load()
})

defineExpose({ refresh })
</script>

<template>
  <div class="flex flex-col gap-3">
    <PageLoading v-if="loading" size="40px" />

    <DashboardErrorState
      v-else-if="loadError"
      testid="friend-list-error"
      :error="loadError"
      @retry="load"
    />

    <template v-else>
      <template v-if="friends.length > 0">
        <TeamFriendCard
          v-for="friend in friends"
          :key="friend.teamFriendId"
          :friend="friend"
          :team-id="teamId"
          :can-edit="canEdit"
          :can-toggle-visibility="canToggleVisibility"
          @unfollow="handleUnfollow"
          @toggle-visibility="handleToggleVisibility"
        />
        <Paginator
          v-if="totalElements > pageSize"
          :rows="pageSize"
          :first="page * pageSize"
          :total-records="totalElements"
          class="mt-2"
          @page="onPageChange"
        />
      </template>
      <div v-else data-testid="friend-list-empty">
        <DashboardEmptyState
          icon="pi pi-users"
          :message="t('friends.list.empty')"
        />
      </div>
    </template>
  </div>
</template>
