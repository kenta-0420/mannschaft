<script setup lang="ts">
/**
 * チームアクセス解析ページ（F10.8 本実装）。
 *
 * 組織版 `organizations/[slug]/analytics.vue` をミラーとして実装。
 * エクスポートは F10.8 対象外（マスター御裁可 2026-07-08・§10.2-C）のため含めない。
 *
 * ビーコン計測: このページ自体の閲覧を `usePageViewBeacon` で計測する。
 * scopeId は TeamShellContext のチーム id（数値）から取得する。
 */
import type { AnalyticsResponse } from '~/types/analytics'
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const analyticsApi = useAnalyticsApi()
const notification = useNotification()
const { t } = useI18n()
const { sendBeacon } = usePageViewBeacon()

// 永続シェルコンテキストからチーム情報を取得
const { team } = useTeamShellContext()

const data = ref<AnalyticsResponse | null>(null)
const loading = ref(true)

async function loadData() {
  loading.value = true
  try {
    data.value = await analyticsApi.getAnalytics('team', teamSlug.value)
  } catch {
    notification.error(t('analytics.loadError'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()

  // このページ自体のページビューを計測（§5.5: 1遷移1送出）
  const teamValue = team.value
  if (teamValue?.id) {
    const numericId = Number(teamValue.id)
    if (numericId > 0) {
      sendBeacon({
        scope: 'TEAM',
        scopeId: numericId,
        contentType: 'PAGE',
        contentId: 0,
        url: route.path,
        title: t('analytics.title'),
      })
    }
  }
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6">
      <h1 class="text-2xl font-bold">{{ $t('analytics.title') }}</h1>
    </div>

    <!-- データエクスポートは F10.8 対象外（マスター御裁可 2026-07-08・§10.2-C）。
         BE エクスポート API 実装後に別 PR で再結線する。 -->

    <PageLoading v-if="loading" />

    <template v-else-if="data">
      <div class="mb-6 grid gap-4 md:grid-cols-4">
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">{{ $t('analytics.summary.totalViews') }}</p>
            <p class="text-3xl font-bold text-primary">
              {{ data.summary.totalViews.toLocaleString() }}
            </p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">{{ $t('analytics.summary.uniqueVisitors') }}</p>
            <p class="text-3xl font-bold">{{ data.summary.uniqueVisitors.toLocaleString() }}</p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">{{ $t('analytics.summary.memberViews') }}</p>
            <p class="text-3xl font-bold text-green-600">
              {{ data.summary.memberViews.toLocaleString() }}
            </p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">{{ $t('analytics.summary.guestViews') }}</p>
            <p class="text-3xl font-bold text-blue-600">
              {{ data.summary.guestViews.toLocaleString() }}
            </p>
          </template>
        </Card>
      </div>

      <SectionCard class="mb-6">
        <PageViewChart :daily="data.daily" :monthly="data.monthly" />
      </SectionCard>

      <SectionCard>
        <ContentRanking :rankings="data.topContent" />
      </SectionCard>
    </template>
  </div>
</template>
