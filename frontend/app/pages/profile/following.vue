<script setup lang="ts">
import type { FollowRecord } from '~/types/social-profile'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { loadMyFollowing, loadMoreMyFollowing, following, followingCursor, followingLoading } =
  useFollowList()
const socialApi = useSocialProfileApi()
const notification = useNotification()
const { handleError } = useErrorHandler()

const totalCount = ref(0)

async function loadData() {
  try {
    await loadMyFollowing(20)
    // フォロー中件数はプロフィールから取得
    const profile = await socialApi.getMyProfile()
    totalCount.value = profile?.followingCount ?? 0
  } catch (e) {
    handleError(e)
  }
}

async function handleUnfollow(record: FollowRecord) {
  try {
    await socialApi.unfollow({ followedType: record.followedType, followedId: record.followedId })
    notification.success(t('label.unfollow') + 'しました')
    await loadData()
  } catch (e) {
    handleError(e)
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-6 flex items-center gap-3">
      <BackButton to="/profile" />
      <PageHeader :title="`${$t('label.following')} ${totalCount}`" />
    </div>

    <PageLoading v-if="followingLoading && following.length === 0" />

    <template v-else>
      <div v-if="following.length > 0" class="space-y-3">
        <div
          v-for="record in following"
          :key="`${record.followedType}-${record.followedId}`"
          class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 px-4 py-3 dark:border-surface-700 dark:bg-surface-900"
        >
          <div class="flex-shrink-0">
            <img
              v-if="record.avatarUrl"
              :src="record.avatarUrl"
              alt=""
              class="h-10 w-10 rounded-full object-cover"
            />
            <div
              v-else
              class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary"
            >
              <i class="pi pi-user" />
            </div>
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate font-medium">{{ record.displayName }}</p>
            <p v-if="record.handle" class="truncate text-sm text-surface-500">@{{ record.handle }}</p>
          </div>
          <Button
            :label="$t('label.unfollow')"
            size="small"
            severity="secondary"
            @click="handleUnfollow(record)"
          />
        </div>
      </div>

      <DashboardEmptyState
        v-else
        icon="pi pi-users"
        :message="$t('label.noFollowing')"
      />

      <div v-if="followingCursor" class="mt-6 flex justify-center">
        <Button
          :label="$t('label.loadMore')"
          :loading="followingLoading"
          severity="secondary"
          @click="loadMoreMyFollowing(20)"
        />
      </div>
    </template>
  </div>
</template>
