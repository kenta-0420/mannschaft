<script setup lang="ts">
import type { PublicActivityResponse } from '~/types/activity'

// 認証不要ページ（公開活動記録は誰でも閲覧可能）
definePageMeta({ auth: false })

const route = useRoute()
const { fetchPublicActivity } = useActivityPublicApi()

const activityId = Number(route.params.id)

const activity = ref<PublicActivityResponse | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const result = await fetchPublicActivity(activityId)
    if (result === null) {
      // PUBLIC でない、または存在しない場合は 404 ページを表示
      throw createError({ statusCode: 404, statusMessage: 'Not Found' })
    }
    activity.value = result
  } finally {
    loading.value = false
  }
}

// OGP タグを動的に設定
useHead(() => {
  if (!activity.value) return {}
  const url =
    typeof window !== 'undefined'
      ? `${window.location.origin}/activity/${activityId}`
      : `/activity/${activityId}`
  return {
    title: activity.value.title,
    meta: [
      { name: 'description', content: activity.value.description ?? activity.value.title },
      { property: 'og:title', content: activity.value.title },
      { property: 'og:description', content: activity.value.description ?? activity.value.title },
      { property: 'og:url', content: url },
      ...(activity.value.imageUrl
        ? [{ property: 'og:image', content: activity.value.imageUrl }]
        : []),
    ],
  }
})

// 公開 URL（シェアパネルに渡す用）
const shareUrl = computed(() => {
  if (typeof window !== 'undefined') {
    return `${window.location.origin}/activity/${activityId}`
  }
  return `/activity/${activityId}`
})

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-8">
    <!-- ロード中 -->
    <div v-if="loading" class="flex flex-col items-center gap-4 p-8">
      <LoadingBounce />
      <p class="text-surface-500">{{ $t('button.loading') }}</p>
    </div>

    <!-- 活動記録詳細 -->
    <template v-else-if="activity">
      <div class="rounded-lg border border-surface-200 bg-white p-6 dark:border-surface-700 dark:bg-surface-800">
        <!-- スコープ名（チーム or 組織：将来対応） -->
        <p v-if="activity.teamName || activity.organizationName" class="mb-2 text-sm text-surface-400">
          {{ activity.teamName ?? activity.organizationName ?? '' }}
        </p>

        <!-- タイトル -->
        <h1 class="mb-3 text-2xl font-bold text-surface-900 dark:text-surface-50">
          {{ activity.title }}
        </h1>

        <!-- 日付・場所 -->
        <div class="mb-4 flex flex-wrap gap-3 text-sm text-surface-500">
          <span>
            <i class="pi pi-calendar mr-1" />{{ activity.activityDate }}
          </span>
          <span v-if="activity.location">
            <i class="pi pi-map-marker mr-1" />{{ activity.location }}
          </span>
          <span v-if="activity.participantCount !== undefined && activity.participantCount !== null">
            <i class="pi pi-users mr-1" />
            {{ $t('activity.participantCount', { count: activity.participantCount }) }}
          </span>
        </div>

        <!-- 画像 -->
        <img
          v-if="activity.imageUrl"
          :src="activity.imageUrl"
          :alt="activity.title"
          class="mb-4 w-full rounded-lg object-cover"
          style="max-height: 360px"
        >

        <!-- 本文 -->
        <p
          v-if="activity.description"
          class="whitespace-pre-wrap text-sm leading-relaxed text-surface-700 dark:text-surface-300"
        >
          {{ activity.description }}
        </p>

        <!-- カスタムフィールド（将来対応：現在はバックエンドから返らない） -->
        <dl
          v-if="activity.customFields && activity.customFields.length > 0"
          class="mt-4 grid grid-cols-1 gap-2 rounded-lg bg-surface-50 p-4 dark:bg-surface-700 sm:grid-cols-2"
        >
          <template v-for="field in activity.customFields" :key="field.fieldId">
            <div v-if="field.value">
              <dt class="text-xs text-surface-400">{{ field.fieldName }}</dt>
              <dd class="text-sm font-medium">{{ field.value }}</dd>
            </div>
          </template>
        </dl>
      </div>

      <!-- シェアパネル -->
      <ActivitySharePanel
        :activity-id="activityId"
        :title="activity.title"
        :url="shareUrl"
        class="mt-6"
      />
    </template>
  </div>
</template>
