<script setup lang="ts">
import type { FollowRecord } from '~/types/social-profile'

const props = defineProps<{
  mode: 'following' | 'followers'
}>()

const socialApi = useSocialProfileApi()
const notification = useNotification()

const records = ref<FollowRecord[]>([])
const loading = ref(true)
const nextCursor = ref<string | null>(null)
const loadingMore = ref(false)

async function loadInitial() {
  loading.value = true
  try {
    const fn = props.mode === 'following' ? socialApi.listFollowing : socialApi.listFollowers
    const res = await fn({ size: 20 })
    records.value = res.data
    nextCursor.value = res.meta.nextCursor
  } catch {
    notification.error('データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const fn = props.mode === 'following' ? socialApi.listFollowing : socialApi.listFollowers
    const res = await fn({ cursor: nextCursor.value, size: 20 })
    records.value.push(...res.data)
    nextCursor.value = res.meta.nextCursor
  } catch {
    notification.error('追加データの取得に失敗しました')
  } finally {
    loadingMore.value = false
  }
}

onMounted(loadInitial)
</script>

<template>
  <div>
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner />
    </div>

    <div v-else-if="records.length === 0" class="py-8 text-center text-surface-500">
      {{ mode === 'following' ? 'フォロー中のユーザーはいません' : 'フォロワーはいません' }}
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="record in records"
        :key="record.id"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <img
          v-if="record.avatarUrl"
          :src="record.avatarUrl"
          alt=""
          class="h-10 w-10 rounded-full object-cover"
        />
        <div v-else class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary">
          <i class="pi pi-user" />
        </div>
        <div class="flex-1">
          <p class="text-sm font-medium">{{ record.displayName }}</p>
          <p v-if="record.handle" class="text-xs text-surface-500">@{{ record.handle }}</p>
        </div>
        <NuxtLink v-if="record.handle" :to="`/social/${record.handle}`">
          <Button label="プロフィール" size="small" severity="secondary" text />
        </NuxtLink>
      </div>

      <div v-if="nextCursor" class="flex justify-center pt-4">
        <Button label="もっと見る" :loading="loadingMore" severity="secondary" outlined @click="loadMore" />
      </div>
    </div>
  </div>
</template>
