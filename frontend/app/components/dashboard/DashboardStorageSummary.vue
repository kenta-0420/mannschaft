<script setup lang="ts">
import type { StorageScopeUsage } from '~/types/storage'
import { formatBytes } from '~/utils/formatBytes'

const { t } = useI18n()
const dashboardStore = useScopeDashboardStore()
const storageApi = useStorageUsageApi()
const { handleApiError } = useErrorHandler()
const usages = ref<StorageScopeUsage[]>([])
const loading = ref(false)
const error = ref(false)
const warningUsage = ref<StorageScopeUsage | null>(null)

function closeWarning(): void {
  warningUsage.value = null
}

const warningVisible = computed({
  get: () => warningUsage.value !== null,
  set: (visible: boolean) => { if (!visible) closeWarning() },
})

async function loadUsage() {
  loading.value = true
  error.value = false
  try {
    usages.value = await storageApi.getMyStorageUsage()
  } catch (caughtError) {
    handleApiError(caughtError, 'dashboard.storageSummary')
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
  if (usage.usagePercent >= 80) return 'text-amber-700 dark:text-amber-400'
  return 'text-surface-600 dark:text-surface-300'
}

function barClass(usage: StorageScopeUsage): string {
  if (usage.usagePercent >= 90) return 'bg-red-500'
  if (usage.usagePercent >= 80) return 'bg-amber-500'
  return 'bg-primary'
}

function displayScopeName(usage: StorageScopeUsage, index: number): string {
  return usage.scopeType === 'PERSONAL' ? scopeLabels.value[index]! : usage.scopeName || scopeLabels.value[index]!
}

function openUsage(usage: StorageScopeUsage | null): void {
  if (usage && usage.includedBytes > 0 && usage.usagePercent >= 90) {
    warningUsage.value = usage
    return
  }
  void navigateTo('/settings/storage')
}

</script>

<template>
  <DashboardWidgetCard
    :title="t('scopeDashboard.storageSummary.title')"
    icon="pi pi-database"
    :scrollable="false"
    class="mb-5"
    data-testid="dashboard-storage-summary"
  >
    <template #actions>
      <NuxtLink to="/settings/storage" class="min-h-11 min-w-11 inline-flex items-center justify-center text-xs text-primary hover:underline">
        {{ t('scopeDashboard.storageSummary.details') }}
      </NuxtLink>
    </template>
    <div v-if="loading" class="grid grid-cols-1 gap-3 md:grid-cols-3" data-testid="storage-loading">
      <Skeleton v-for="index in 3" :key="index" height="4.5rem" />
    </div>
    <Message v-else-if="error" severity="error" :closable="false" data-testid="storage-error">
      <div class="flex items-center justify-between gap-3">
        <span>{{ t('scopeDashboard.storageSummary.error') }}</span>
        <Button size="small" text class="min-h-11 min-w-11" :label="t('scopeDashboard.storageSummary.retry')" data-testid="storage-retry" @click="loadUsage" />
      </div>
    </Message>
    <div v-else class="grid grid-cols-1 gap-3 md:grid-cols-3">
      <button v-for="(usage, index) in selectedUsages" :key="scopeLabels[index]" type="button" class="min-h-11 w-full min-w-0 rounded-md bg-surface-50 p-3 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary dark:bg-surface-800" :data-testid="`storage-card-${index}`" @click="openUsage(usage)">
        <div class="mb-2 flex items-center justify-between gap-2 text-sm">
          <span class="truncate font-medium">{{ usage ? displayScopeName(usage, index) : scopeLabels[index] }}</span>
          <span v-if="usage && usage.includedBytes > 0" class="shrink-0" :class="usageClass(usage)">{{ usage.usagePercent.toFixed(1) }}%</span>
        </div>
        <template v-if="usage">
          <div v-if="usage.includedBytes > 0" class="h-2 overflow-hidden rounded-full bg-surface-200 dark:bg-surface-700" role="progressbar" :aria-label="t('scopeDashboard.storageSummary.meterLabel', { scope: displayScopeName(usage, index) })" :aria-valuenow="gaugePercent(usage)" :aria-valuetext="t('scopeDashboard.storageSummary.meterValue', { percent: usage.usagePercent.toFixed(1) })" aria-valuemin="0" aria-valuemax="100">
            <div class="h-full rounded-full transition-all" :class="barClass(usage)" :style="{ width: `${gaugePercent(usage)}%` }" />
          </div>
          <p v-if="usage.includedBytes > 0" class="mt-1 text-xs text-surface-500">{{ formatBytes(usage.usedBytes) }} / {{ formatBytes(usage.includedBytes) }}</p>
          <p v-else class="mt-1 text-xs text-surface-500">{{ t('scopeDashboard.storageSummary.unconfigured') }}</p>
        </template>
        <p v-else class="text-xs text-surface-500">{{ t('scopeDashboard.storageSummary.not_available') }}</p>
      </button>
    </div>
    <Dialog
      v-if="warningUsage"
      v-model:visible="warningVisible"
      modal
      closable
      close-on-escape
      :header="t('scopeDashboard.storageSummary.warningTitle')"
      :aria-label="t('scopeDashboard.storageSummary.warningTitle')"
      class="w-full max-w-md"
      :style="{ width: 'min(28rem, calc(100vw - 2rem))' }"
      :breakpoints="{ '640px': 'calc(100vw - 2rem)' }"
      @hide="closeWarning"
    >
      <p class="mb-2">{{ t('scopeDashboard.storageSummary.warningMessage', { scope: displayScopeName(warningUsage, selectedUsages.findIndex(item => item === warningUsage)), percent: warningUsage.usagePercent.toFixed(1) }) }}</p>
      <div class="flex flex-wrap justify-end gap-2">
        <Button text class="min-h-11 min-w-11" :label="t('scopeDashboard.storageSummary.cancel')" @click="closeWarning" />
        <Button text class="min-h-11 min-w-11" :label="t('scopeDashboard.storageSummary.checkStorage')" @click="closeWarning(); navigateTo('/settings/storage')" />
        <Button class="min-h-11 min-w-11" :label="t('scopeDashboard.storageSummary.viewPlans')" @click="closeWarning(); navigateTo('/billing/plans')" />
      </div>
    </Dialog>
  </DashboardWidgetCard>
</template>
