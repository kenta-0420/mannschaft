<script setup lang="ts">
import type { SecurityScanConclusion, SecurityScanStatusResponse } from '~/types/system-admin'

/**
 * セキュリティスキャン状態カード。
 *
 * <p>GitHub Actions の OWASP Dependency-Check 週次スキャン状態を表示する。
 * SYSTEM_ADMIN 向けシステム管理ダッシュボードに配置するウィジェット。</p>
 */
const { t } = useI18n()
const securityScanApi = useSecurityScanApi()

const status = ref<SecurityScanStatusResponse | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await securityScanApi.fetchStatus()
    status.value = res.data
  } catch (e) {
    console.error('SystemAdminSecurityScanCard: failed to load security scan status', e)
    status.value = { conclusion: 'UNKNOWN', runUrl: null, runAt: null }
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---- 表示ロジック ----

const badgeClass = computed(() => {
  if (!status.value) return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
  const map: Record<SecurityScanConclusion, string> = {
    SUCCESS: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    FAILURE: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    IN_PROGRESS: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
    UNKNOWN: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400',
  }
  return map[status.value.conclusion] ?? map.UNKNOWN
})

const borderClass = computed(() => {
  if (!status.value) return ''
  if (status.value.conclusion === 'FAILURE') {
    return 'border-red-200 dark:border-red-800'
  }
  return ''
})

const conclusionLabel = computed(() => {
  if (!status.value) return t('systemAdmin.securityScan.status.UNKNOWN')
  return t(`systemAdmin.securityScan.status.${status.value.conclusion}`)
})

const formattedRunAt = computed(() => {
  if (!status.value?.runAt) return null
  return new Date(status.value.runAt).toLocaleString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Tokyo',
  })
})
</script>

<template>
  <section class="mb-6">
    <h2
      class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
    >
      <i class="pi pi-shield" />{{ t('systemAdmin.securityScan.title') }}
    </h2>

    <div
      class="flex flex-col gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800 sm:flex-row sm:items-center sm:justify-between"
      :class="borderClass"
    >
      <!-- ローディング中 -->
      <template v-if="loading">
        <div class="flex items-center gap-3">
          <i class="pi pi-spin pi-spinner text-surface-400" />
          <span class="text-sm text-surface-500">{{ t('systemAdmin.securityScan.title') }}...</span>
        </div>
      </template>

      <template v-else>
        <!-- 左側: バッジ + 最終スキャン日時 -->
        <div class="flex flex-col gap-1">
          <div class="flex items-center gap-2">
            <span
              class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold"
              :class="badgeClass"
            >
              <!-- SUCCESS: チェックアイコン -->
              <i
                v-if="status?.conclusion === 'SUCCESS'"
                class="pi pi-check-circle mr-1 text-xs"
              />
              <!-- FAILURE: 警告アイコン -->
              <i
                v-else-if="status?.conclusion === 'FAILURE'"
                class="pi pi-exclamation-triangle mr-1 text-xs"
              />
              <!-- IN_PROGRESS: スピナー -->
              <i
                v-else-if="status?.conclusion === 'IN_PROGRESS'"
                class="pi pi-spin pi-spinner mr-1 text-xs"
              />
              <!-- UNKNOWN: ダッシュ -->
              <i v-else class="pi pi-minus mr-1 text-xs" />
              {{ conclusionLabel }}
            </span>
          </div>

          <p
            v-if="formattedRunAt"
            class="text-xs text-surface-500"
          >
            {{ t('systemAdmin.securityScan.lastScanned') }}: {{ formattedRunAt }}
          </p>
        </div>

        <!-- 右側: 詳細リンク（FAILURE のときのみ強調表示） -->
        <div v-if="status?.runUrl" class="flex-shrink-0">
          <a
            :href="status.runUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors"
            :class="
              status?.conclusion === 'FAILURE'
                ? 'bg-red-600 text-white hover:bg-red-700 dark:bg-red-700 dark:hover:bg-red-800'
                : 'border border-surface-300 text-surface-600 hover:bg-surface-100 dark:border-surface-500 dark:text-surface-300 dark:hover:bg-surface-700'
            "
          >
            <i class="pi pi-external-link text-xs" />
            {{ t('systemAdmin.securityScan.viewDetails') }}
          </a>
        </div>
      </template>
    </div>
  </section>
</template>
