<script setup lang="ts">
import type { AnalyticsResponse } from '~/types/analytics'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const analyticsApi = useAnalyticsApi()
const notification = useNotification()

const data = ref<AnalyticsResponse | null>(null)
const loading = ref(true)
const showExport = ref(false)

async function loadData() {
  loading.value = true
  try {
    data.value = await analyticsApi.getAnalytics('team', teamId.value)
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
      <PageHeader title="アクセス解析" />
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

      <SectionCard class="mb-6">
        <PageViewChart :daily="data.daily" :monthly="data.monthly" />
      </SectionCard>

      <SectionCard>
        <ContentRanking :rankings="data.topContent" />
      </SectionCard>
    </template>

    <ExportDialog v-model:visible="showExport" scope-type="team" :scope-id="teamId" />
  </div>
</template>
