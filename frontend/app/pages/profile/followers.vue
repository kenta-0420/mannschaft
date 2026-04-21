<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { loadMyFollowers, loadMoreMyFollowers, followers, followersCursor, followersLoading } =
  useFollowList()
const socialApi = useSocialProfileApi()
const { handleError } = useErrorHandler()

const totalCount = ref(0)

async function loadData() {
  try {
    await loadMyFollowers(20)
    // フォロワー件数はプロフィールから取得
    const profile = await socialApi.getMyProfile()
    totalCount.value = profile?.followerCount ?? 0
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
      <PageHeader :title="`${$t('label.followers')} ${totalCount}`" />
    </div>

    <PageLoading v-if="followersLoading && followers.length === 0" />

    <template v-else>
      <div v-if="followers.length > 0" class="space-y-3">
        <div
          v-for="record in followers"
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
        </div>
      </div>

      <DashboardEmptyState
        v-else
        icon="pi pi-users"
        :message="$t('label.noFollowers')"
      />

      <div v-if="followersCursor" class="mt-6 flex justify-center">
        <Button
          :label="$t('label.loadMore')"
          :loading="followersLoading"
          severity="secondary"
          @click="loadMoreMyFollowers(20)"
        />
      </div>
    </template>
  </div>
</template>
