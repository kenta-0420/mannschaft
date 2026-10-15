<script setup lang="ts">
import type { StorageScopeUsage } from '~/types/storage'
import { formatBytes } from '~/utils/formatBytes'

/**
 * F13 ストレージ容量使用量画面
 * 所属スコープ（個人・チーム・組織）ごとの容量使用状況をゲージで一覧表示する
 */

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const storageApi = useStorageUsageApi()

const usages = ref<StorageScopeUsage[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)

async function loadUsage() {
  loading.value = true
  errorMsg.value = null
  try {
    usages.value = await storageApi.getMyStorageUsage()
  } catch (err) {
    errorMsg.value = t('settings.storage.error_fetch')
    console.error(err)
  } finally {
    loading.value = false
  }
}

onMounted(loadUsage)

const personalUsages = computed(() => usages.value.filter((u) => u.scopeType === 'PERSONAL'))
const teamUsages = computed(() => usages.value.filter((u) => u.scopeType === 'TEAM'))
const orgUsages = computed(() => usages.value.filter((u) => u.scopeType === 'ORGANIZATION'))

/** PrimeVue ProgressBar 用: 0〜100 にクランプ */
function clampedPercent(u: StorageScopeUsage): number {
  return Math.min(100, Math.round(u.usagePercent))
}

/** ゲージの色: 90% 以上は警告色 */
function gaugeClass(u: StorageScopeUsage): string {
  return u.usagePercent >= 90 ? 'storage-bar--danger' : ''
}

/** スコープ名表示: PERSONAL は i18n キー、それ以外はそのまま */
function scopeLabel(u: StorageScopeUsage): string {
  return u.scopeType === 'PERSONAL' ? t('settings.storage.scope_personal') : u.scopeName
}
</script>

<template>
  <div>
    <PageHeader :title="t('settings.storage.page_title')" />

    <div class="mx-auto max-w-2xl">
      <!-- ロード中 -->
      <PageLoading v-if="loading" />

      <!-- エラー -->
      <Message v-else-if="errorMsg" severity="error" :closable="false" class="mb-4">
        {{ errorMsg }}
      </Message>

      <template v-else-if="usages.length > 0">
        <!-- 個人 -->
        <SectionCard v-if="personalUsages.length > 0" class="mb-4">
          <template #header>
            <h2 class="text-base font-semibold">{{ t('settings.storage.group_personal') }}</h2>
          </template>
          <div class="flex flex-col gap-4">
            <div v-for="u in personalUsages" :key="`personal-${u.scopeId}`" class="flex flex-col gap-2">
              <div class="flex items-center justify-between text-sm">
                <span class="font-medium">{{ scopeLabel(u) }}</span>
                <span class="text-surface-500">
                  {{ t('settings.storage.file_count', { count: u.fileCount }) }}
                </span>
              </div>
              <ProgressBar
                :value="clampedPercent(u)"
                :class="gaugeClass(u)"
                class="storage-progress-bar"
              />
              <div class="flex items-center justify-between text-xs text-surface-500">
                <span>
                  {{ formatBytes(u.usedBytes) }} / {{ formatBytes(u.includedBytes) }}
                  <span v-if="u.maxBytes !== null" class="ml-1">
                    ({{ t('settings.storage.limit') }}: {{ formatBytes(u.maxBytes) }})
                  </span>
                  <span v-else class="ml-1">
                    ({{ t('settings.storage.unlimited') }})
                  </span>
                </span>
                <span :class="u.usagePercent >= 90 ? 'font-semibold text-red-500' : ''">
                  {{ u.usagePercent.toFixed(1) }}%
                </span>
              </div>
            </div>
          </div>
        </SectionCard>

        <!-- チーム -->
        <SectionCard v-if="teamUsages.length > 0" class="mb-4">
          <template #header>
            <h2 class="text-base font-semibold">{{ t('settings.storage.group_team') }}</h2>
          </template>
          <div class="flex flex-col gap-4">
            <div v-for="u in teamUsages" :key="`team-${u.scopeId}`" class="flex flex-col gap-2">
              <div class="flex items-center justify-between text-sm">
                <span class="font-medium">{{ scopeLabel(u) }}</span>
                <span class="text-surface-500">
                  {{ t('settings.storage.file_count', { count: u.fileCount }) }}
                </span>
              </div>
              <ProgressBar
                :value="clampedPercent(u)"
                :class="gaugeClass(u)"
                class="storage-progress-bar"
              />
              <div class="flex items-center justify-between text-xs text-surface-500">
                <span>
                  {{ formatBytes(u.usedBytes) }} / {{ formatBytes(u.includedBytes) }}
                  <span v-if="u.maxBytes !== null" class="ml-1">
                    ({{ t('settings.storage.limit') }}: {{ formatBytes(u.maxBytes) }})
                  </span>
                  <span v-else class="ml-1">
                    ({{ t('settings.storage.unlimited') }})
                  </span>
                </span>
                <span :class="u.usagePercent >= 90 ? 'font-semibold text-red-500' : ''">
                  {{ u.usagePercent.toFixed(1) }}%
                </span>
              </div>
            </div>
          </div>
        </SectionCard>

        <!-- 組織 -->
        <SectionCard v-if="orgUsages.length > 0" class="mb-4">
          <template #header>
            <h2 class="text-base font-semibold">{{ t('settings.storage.group_org') }}</h2>
          </template>
          <div class="flex flex-col gap-4">
            <div v-for="u in orgUsages" :key="`org-${u.scopeId}`" class="flex flex-col gap-2">
              <div class="flex items-center justify-between text-sm">
                <span class="font-medium">{{ scopeLabel(u) }}</span>
                <span class="text-surface-500">
                  {{ t('settings.storage.file_count', { count: u.fileCount }) }}
                </span>
              </div>
              <ProgressBar
                :value="clampedPercent(u)"
                :class="gaugeClass(u)"
                class="storage-progress-bar"
              />
              <div class="flex items-center justify-between text-xs text-surface-500">
                <span>
                  {{ formatBytes(u.usedBytes) }} / {{ formatBytes(u.includedBytes) }}
                  <span v-if="u.maxBytes !== null" class="ml-1">
                    ({{ t('settings.storage.limit') }}: {{ formatBytes(u.maxBytes) }})
                  </span>
                  <span v-else class="ml-1">
                    ({{ t('settings.storage.unlimited') }})
                  </span>
                </span>
                <span :class="u.usagePercent >= 90 ? 'font-semibold text-red-500' : ''">
                  {{ u.usagePercent.toFixed(1) }}%
                </span>
              </div>
            </div>
          </div>
        </SectionCard>
      </template>

      <!-- データ無し -->
      <div v-else class="py-10 text-center text-surface-400">
        {{ t('settings.storage.empty') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 90% 以上のゲージは警告色 */
:deep(.storage-bar--danger .p-progressbar-value) {
  background-color: var(--p-red-500);
}
</style>
