<script setup lang="ts">
import type { StorageScopeUsage } from '~/types/storage'
import { formatBytes } from '~/utils/formatBytes'

const { t } = useI18n()
const dashboardStore = useScopeDashboardStore()
const storageApi = useStorageUsageApi()
const usages = ref<StorageScopeUsage[]>([])
const loading = ref(false)
const error = ref(false)

async function loadUsage() {
  loading.value = true
  error.value = false
  try {
    usages.value = await storageApi.getMyStorageUsage()
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

onMounted(loadUsage)

const selectedUsages = computed<(StorageScopeUsage | null)[]>(() => [
  usages.value.find(usage => usage.scopeType === 'PERSONAL') ?? null,
  usages.value.find((usage) => usage.scopeType === 'TEAM' && dashboardStore.selectedTeamId !== null
    && (usage.slug === dashboardStore.selectedTeamId || String(usage.scopeId) === dashboardStore.selectedTeamId)) ?? null,
  usages.value.find((usage) => usage.scopeType === 'ORGANIZATION' && dashboardStore.selectedOrgId !== null
    && (usage.slug === dashboardStore.selectedOrgId || String(usage.scopeId) === dashboardStore.selectedOrgId)) ?? null,
])

const scopeLabels = computed(() => [
  t('scopeDashboard.storageSummary.personal'),
  t('scopeDashboard.storageSummary.team'),
  t('scopeDashboard.storageSummary.organization'),
])

function gaugePercent(usage: StorageScopeUsage): number {
  return Math.min(100, Math.max(0, usage.usagePercent))
}

function usageClass(usage: StorageScopeUsage): string {
  if (usage.usagePercent >= 90) return 'text-red-600 dark:text-red-400'
  if (usage.usagePercent >= 80) return 'text-amber-600 dark:text-amber-400'
  return 'text-surface-600 dark:text-surface-300'
}

function barClass(usage: StorageScopeUsage): string {
  if (usage.usagePercent >= 90) return 'bg-red-500'
  if (usage.usagePercent >= 80) return 'bg-amber-500'
  return 'bg-primary'
}
</script>

<template>
  <section class="mb-5 rounded-lg border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-900" data-testid="dashboard-storage-summary" :aria-label="t('scopeDashboard.storageSummary.title')">
    <div class="mb-3 flex items-center justify-between gap-2">
      <h2 class="text-sm font-semibold">{{ t('scopeDashboard.storageSummary.title') }}</h2>
      <NuxtLink to="/settings/storage" class="text-xs text-primary hover:underline">{{ t('scopeDashboard.storageSummary.details') }}</NuxtLink>
    </div>
    <div v-if="loading" class="grid grid-cols-1 gap-3 md:grid-cols-3" data-testid="storage-loading">
      <Skeleton v-for="index in 3" :key="index" height="4.5rem" />
    </div>
    <Message v-else-if="error" severity="error" :closable="false" data-testid="storage-error">
      <div class="flex items-center justify-between gap-3">
        <span>{{ t('scopeDashboard.storageSummary.error') }}</span>
        <Button size="small" text :label="t('scopeDashboard.storageSummary.retry')" data-testid="storage-retry" @click="loadUsage" />
      </div>
    </Message>
    <div v-else class="grid grid-cols-1 gap-3 md:grid-cols-3">
      <article v-for="(usage, index) in selectedUsages" :key="scopeLabels[index]" class="min-w-0 rounded-md bg-surface-50 p-3 dark:bg-surface-800" :data-testid="`storage-card-${index}`">
        <div class="mb-2 flex items-center justify-between gap-2 text-sm">
          <span class="truncate font-medium">{{ usage?.scopeName || scopeLabels[index] }}</span>
          <span v-if="usage" class="shrink-0" :class="usageClass(usage)">{{ usage.usagePercent.toFixed(1) }}%</span>
        </div>
        <template v-if="usage">
          <div class="h-2 overflow-hidden rounded-full bg-surface-200 dark:bg-surface-700" role="progressbar" :aria-valuenow="gaugePercent(usage)" aria-valuemin="0" aria-valuemax="100">
            <div class="h-full rounded-full transition-all" :class="barClass(usage)" :style="{ width: `${gaugePercent(usage)}%` }" />
          </div>
          <p v-if="usage.includedBytes > 0" class="mt-1 text-xs text-surface-500">{{ formatBytes(usage.usedBytes) }} / {{ formatBytes(usage.includedBytes) }}</p>
          <p v-else class="mt-1 text-xs text-surface-500">{{ t('scopeDashboard.storageSummary.unconfigured') }}</p>
        </template>
        <p v-else class="text-xs text-surface-500">{{ t('scopeDashboard.storageSummary.not_available') }}</p>
      </article>
    </div>
  </section>
</template>
