<script setup lang="ts">
import type { FollowRecord } from '~/types/social-profile'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const userId = Number(route.params.userId)
const { getUserFollowers } = useFollowList()
const { handleError } = useErrorHandler()

const records = ref<FollowRecord[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(true)
const loadingMore = ref(false)
const isPrivate = ref(false)

async function loadData(cursor?: string) {
  if (cursor) {
    loadingMore.value = true
  } else {
    loading.value = true
    records.value = []
  }
  isPrivate.value = false
  try {
    const res = await getUserFollowers(userId, { cursor, size: 20 })
    if (cursor) {
      records.value.push(...res.data)
    } else {
      records.value = res.data
    }
    nextCursor.value = res.meta.nextCursor
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 403) {
      isPrivate.value = true
    } else {
      handleError(e)
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

onMounted(() => loadData())
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-6 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="$t('label.followers')" />
    </div>

    <PageLoading v-if="loading" />

    <!-- 非公開メッセージ -->
    <div
      v-else-if="isPrivate"
      class="flex flex-col items-center gap-3 py-16 text-center text-surface-500"
    >
      <i class="pi pi-lock text-4xl" />
      <p>{{ $t('label.followListPrivate') }}</p>
    </div>

    <template v-else>
      <div v-if="records.length > 0" class="space-y-3">
        <div
          v-for="record in records"
          :key="`${record.followedType}-${record.followedId}`"
          class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 px-4 py-3 dark:border-surface-700 dark:bg-surface-900"
        >
          <div class="flex-shrink-0">
            <img
              v-if="record.avatarUrl"
              :src="record.avatarUrl"
              alt=""
              class="h-10 w-10 rounded-full object-cover"
            >
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

      <div v-if="nextCursor" class="mt-6 flex justify-center">
        <Button
          :label="$t('label.loadMore')"
          :loading="loadingMore"
          severity="secondary"
          @click="loadData(nextCursor ?? undefined)"
        />
      </div>
    </template>
  </div>
</template>
