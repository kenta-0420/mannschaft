<script setup lang="ts">
import type { BatchEndpointSummary } from '~/types/system-admin'

/**
 * F10.X 第三陣（丁組） — システム管理ダッシュボード用バッチサマリー。
 *
 * <p>登録済みバッチ一覧（{@code /api/v1/system-admin/batch}）から直近実行のステータスを集計し、
 * 「成功 / 失敗 / 実行中」のカウントとバッチ管理ページへのリンクを表示する。</p>
 */
const { t } = useI18n()
const localePath = useLocalePath()
const batchApi = useSystemAdminBatchApi()

const batches = ref<BatchEndpointSummary[]>([])
const loading = ref(true)

const successCount = computed(() => batches.value.filter((b) => b.lastStatus === 'SUCCESS').length)
const failedCount = computed(() => batches.value.filter((b) => b.lastStatus === 'FAILED').length)
const runningCount = computed(() => batches.value.filter((b) => b.lastStatus === 'RUNNING').length)
const totalCount = computed(() => batches.value.length)

async function load() {
  loading.value = true
  try {
    const res = await batchApi.listBatches()
    batches.value = res.data
  } catch (e) {
    console.error('SystemAdminBatchSummary: failed to load batches', e)
    batches.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="mb-6">
    <h2
      class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
    >
      <i class="pi pi-cog" />{{ t('systemAdmin.batches.summary.title') }}
    </h2>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <span class="mb-1 text-xs text-surface-500">{{ t('systemAdmin.batches.title') }}</span>
        <span class="text-2xl font-bold text-surface-700 dark:text-surface-200">
          {{ loading ? '-' : totalCount }}
        </span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <span class="mb-1 text-xs text-surface-500">{{ t('systemAdmin.batches.summary.success') }}</span>
        <span class="text-2xl font-bold text-green-600">
          {{ loading ? '-' : successCount }}
        </span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        :class="failedCount > 0 ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20' : ''"
      >
        <span class="mb-1 text-xs text-surface-500">{{ t('systemAdmin.batches.summary.failed') }}</span>
        <span
          class="text-2xl font-bold"
          :class="failedCount > 0 ? 'text-red-600' : 'text-surface-700 dark:text-surface-200'"
        >
          {{ loading ? '-' : failedCount }}
        </span>
      </div>
      <div
        class="flex flex-col items-start justify-center rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <span class="mb-1 text-xs text-surface-500">{{ t('systemAdmin.batches.status.running') }}</span>
        <span class="mb-2 text-2xl font-bold text-blue-600">
          {{ loading ? '-' : runningCount }}
        </span>
        <NuxtLink
          :to="localePath('/system-admin/batches')"
          class="text-xs text-primary-600 hover:underline dark:text-primary-400"
        >
          {{ t('systemAdmin.batches.summary.viewAll') }} →
        </NuxtLink>
      </div>
    </div>
  </section>
</template>
