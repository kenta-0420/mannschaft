<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { getMyPerformance } = usePerformanceApi()
const { showError } = useNotification()

const data = ref<Record<string, unknown> | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyPerformance()
    data.value = res.data
  } catch {
    showError('パフォーマンスデータの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <h1 class="mb-6 text-2xl font-bold">マイパフォーマンス</h1>
    <PageLoading v-if="loading" size="40px" />
    <template v-else-if="data">
      <div class="grid gap-4 md:grid-cols-3">
        <div
          v-for="(value, key) in data.metrics"
          :key="key"
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center"
        >
          <p class="text-sm text-surface-500">{{ key }}</p>
          <p class="text-2xl font-bold text-primary">{{ value }}</p>
        </div>
      </div>
      <div v-if="data.records?.length" class="mt-6">
        <h2 class="mb-3 text-lg font-semibold">記録一覧</h2>
        <div class="flex flex-col gap-2">
          <div
            v-for="r in data.records"
            :key="r.id"
            class="rounded-xl border border-surface-200 bg-surface-0 p-4"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm font-medium">{{ r.metricName }}</span>
              <span class="text-sm font-bold text-primary">{{ r.value }}</span>
            </div>
            <p class="text-xs text-surface-400">{{ r.recordedAt }} - {{ r.teamName }}</p>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="py-12 text-center">
      <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">パフォーマンスデータがありません</p>
    </div>
  </div>
</template>
