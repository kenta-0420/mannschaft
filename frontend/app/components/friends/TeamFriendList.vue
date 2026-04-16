<script setup lang="ts">
/**
 * F01.5 フレンドチーム一覧。
 *
 * 役割:
 * - {@link useFriendTeamsApi.listFriends} でフレンド一覧を取得し、
 *   {@link TeamFriendCard} を map 表示する。
 * - ページネーション UI（PrimeVue Paginator）を提供する。
 * - 空状態は {@link DashboardEmptyState} を使用。
 * - ローディング中は {@link PageLoading} を使用。
 *
 * Props:
 *   teamId  — 自チーム ID
 *   canEdit — ADMIN 相当の編集操作が可能か（カードに伝搬）
 *   canToggleVisibility — ADMIN のみ公開設定可能か（カードに伝搬）
 *
 * Emits:
 *   refresh — 親へリフレッシュが行われたことを通知（カウント再取得等に使う）
 */
import type { TeamFriendView } from '~/types/friends'

const props = defineProps<{
  teamId: number
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
const page = ref(0)
const pageSize = ref(20)
const totalElements = ref(0)

async function load() {
  loading.value = true
  try {
    const response = await listFriends(props.teamId, {
      page: page.value,
      size: pageSize.value,
    })
    friends.value = response.data
    totalElements.value = response.pagination.totalElements
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    loading.value = false
  }
}

async function refresh() {
  await load()
  emit('refresh')
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
      }
      catch (error) {
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
          :total-records="totalElements"
          class="mt-2"
          @page="onPageChange"
        />
      </template>
      <DashboardEmptyState
        v-else
        icon="pi pi-users"
        :message="t('friends.list.empty')"
      />
    </template>
  </div>
</template>
