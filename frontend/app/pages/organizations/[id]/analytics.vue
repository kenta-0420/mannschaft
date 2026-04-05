<script setup lang="ts">
import type { AnalyticsResponse } from '~/types/analytics'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const analyticsApi = useAnalyticsApi()
const notification = useNotification()

const data = ref<AnalyticsResponse | null>(null)
const loading = ref(true)
const showExport = ref(false)

async function loadData() {
  loading.value = true
  try {
    data.value = await analyticsApi.getAnalytics('organization', orgId.value)
  } catch {
    notification.error('アクセス解析データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">アクセス解析</h1>
      <Button
        label="データエクスポート"
        icon="pi pi-download"
        severity="secondary"
        @click="showExport = true"
      />
    </div>

    <PageLoading v-if="loading" />

    <template v-else-if="data">
      <div class="mb-6 grid gap-4 md:grid-cols-4">
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">総PV</p>
            <p class="text-3xl font-bold text-primary">
              {{ data.summary.totalViews.toLocaleString() }}
            </p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">ユニーク訪問者</p>
            <p class="text-3xl font-bold">{{ data.summary.uniqueVisitors.toLocaleString() }}</p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">メンバー閲覧</p>
            <p class="text-3xl font-bold text-green-600">
              {{ data.summary.memberViews.toLocaleString() }}
            </p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">ゲスト閲覧</p>
            <p class="text-3xl font-bold text-blue-600">
              {{ data.summary.guestViews.toLocaleString() }}
            </p>
          </template>
        </Card>
      </div>

      <div
        class="mb-6 rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <PageViewChart :daily="data.daily" :monthly="data.monthly" />
      </div>

      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <ContentRanking :rankings="data.topContent" />
      </div>
    </template>

    <ExportDialog v-model:visible="showExport" scope-type="organization" :scope-id="orgId" />
  </div>
</template>
