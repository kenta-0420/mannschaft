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
      // NOTE: og:image は設定しない。公開活動記録 API（PublicActivityDetail）は
      // 御裁可済み 8 項目のみを返し、画像 URL を含まないため。
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
        <!-- スコープ名（チーム or 組織）: BE の PublicScopeRef から取得する -->
        <p v-if="activity.scopeRef?.scopeName" class="mb-2 text-sm text-surface-400">
          {{ activity.scopeRef.scopeName }}
        </p>

        <!-- タイトル -->
        <h1 class="mb-3 text-2xl font-bold text-surface-900 dark:text-surface-50">
          {{ activity.title }}
        </h1>

        <!--
          日付
          NOTE: 開催場所（location）・参加人数・画像・カスタムフィールドは表示しない。
          公開活動記録 API は御裁可済み 8 項目のみを返し、これらは禁則フィールドとして
          意図的に含まれていない（BE: PublicActivityDetail の Javadoc 参照）。
        -->
        <div class="mb-4 flex flex-wrap gap-3 text-sm text-surface-500">
          <span>
            <i class="pi pi-calendar mr-1" />{{ activity.activityDate }}
          </span>
        </div>

        <!-- 本文 -->
        <p
          v-if="activity.description"
          class="whitespace-pre-wrap text-sm leading-relaxed text-surface-700 dark:text-surface-300"
        >
          {{ activity.description }}
        </p>
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
