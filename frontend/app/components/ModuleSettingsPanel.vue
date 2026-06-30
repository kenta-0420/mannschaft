<script setup lang="ts">
import type { ModuleCatalog, ModuleCatalogItem } from '~/composables/useAdminDashboardApi'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const { t } = useI18n()
const adminApi = useAdminDashboardApi()
const notification = useNotification()

const modules = ref<ModuleCatalogItem[]>([])
const planLimit = ref(0)
const enabledCount = ref(0)
const hasPaidPlan = ref(false)
const loading = ref(false)
const togglingIds = ref<number[]>([])
const showGuide = ref(false)

async function fetchCatalog() {
  loading.value = true
  try {
    const data: ModuleCatalog = await adminApi.getModuleCatalog(props.scopeType, props.scopeId)
    modules.value = data.modules ?? []
    planLimit.value = data.planLimit ?? 0
    enabledCount.value = data.enabledCount ?? 0
    hasPaidPlan.value = data.hasPaidPlan ?? false
  }
  catch {
    notification.error(t('module_settings.fetch_error'))
  }
  finally {
    loading.value = false
  }
}

/**
 * トグルを無効化する理由を返す（バッジ表示にも使う）。
 * enabledCount が変わると上限判定も変わるため、computed ではなく関数で都度評価する。
 * 優先順位: レベル制限 → 有料プラン → 上限到達。
 */
function disabledReason(item: ModuleCatalogItem): 'level' | 'paid' | 'limit' | null {
  if (item.levelAvailable === false) return 'level'
  if (item.requiresPaidPlan && !hasPaidPlan.value) return 'paid'
  if (!item.isEnabled && enabledCount.value >= planLimit.value) return 'limit'
  return null
}

function isToggling(item: ModuleCatalogItem): boolean {
  return item.moduleId != null && togglingIds.value.includes(item.moduleId)
}

function isToggleDisabled(item: ModuleCatalogItem): boolean {
  return disabledReason(item) !== null || isToggling(item)
}

function badgeLabel(item: ModuleCatalogItem): string | null {
  switch (disabledReason(item)) {
    case 'level':
      return t('module_settings.badge.level_unavailable')
    case 'paid':
      return t('module_settings.badge.paid_plan')
    case 'limit':
      return t('module_settings.badge.limit_reached', { limit: planLimit.value })
    default:
      return null
  }
}

/** 業務エラーコードを利用者向けメッセージへ変換する（握り潰さない）。 */
function resolveToggleErrorMessage(err: unknown): string {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  switch (code) {
    case 'TMPL_004':
      return t('module_settings.error.paid_required')
    case 'TMPL_003':
      return t('module_settings.error.limit_reached')
    case 'TMPL_005':
      return t('module_settings.error.level_unavailable')
    default:
      return t('module_settings.error.generic')
  }
}

async function toggle(item: ModuleCatalogItem, enabled: boolean) {
  const id = item.moduleId
  if (id == null || isToggleDisabled(item)) return
  togglingIds.value.push(id)
  try {
    await adminApi.toggleModule(props.scopeType, props.scopeId, String(id), enabled)
    item.isEnabled = enabled
    enabledCount.value += enabled ? 1 : -1
    notification.success(
      enabled
        ? t('module_settings.toggle_success_on', { name: item.name ?? '' })
        : t('module_settings.toggle_success_off', { name: item.name ?? '' }),
    )
  }
  catch (err) {
    notification.error(resolveToggleErrorMessage(err))
  }
  finally {
    togglingIds.value = togglingIds.value.filter((tid) => tid !== id)
  }
}

onMounted(fetchCatalog)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <p class="text-sm text-gray-500">
        {{ t('module_settings.enabled_count', { count: enabledCount, limit: planLimit }) }}
      </p>
      <div class="flex items-center gap-1">
        <Button
          icon="pi pi-question-circle"
          :label="t('module_settings_guide.help_button')"
          text
          size="small"
          @click="showGuide = true"
        />
        <Button
          icon="pi pi-refresh"
          text
          rounded
          size="small"
          :loading="loading"
          @click="fetchCatalog"
        />
      </div>
    </div>

    <div v-if="loading" class="flex justify-center py-10">
      <LoadingBounce />
    </div>

    <div
      v-else-if="modules.length === 0"
      class="rounded-lg border border-dashed border-gray-300 py-10 text-center text-sm text-gray-500"
    >
      <i class="pi pi-box mb-2 block text-2xl" />
      <p class="font-medium">{{ t('module_settings.empty') }}</p>
    </div>

    <div v-else class="grid grid-cols-1 gap-2 sm:grid-cols-2">
      <div
        v-for="item in modules"
        :key="item.moduleId"
        class="flex items-start justify-between rounded-xl border p-4 transition-colors"
        :class="
          item.isEnabled
            ? 'border-primary-200 bg-primary-50/40 dark:border-primary-800 dark:bg-primary-950/30'
            : 'border-surface-200 bg-surface-0 dark:border-surface-600 dark:bg-surface-800'
        "
      >
        <div class="flex min-w-0 items-start gap-3">
          <div
            class="flex size-9 shrink-0 items-center justify-center rounded-lg"
            :class="
              item.isEnabled
                ? 'bg-primary-100 text-primary dark:bg-primary-900'
                : 'bg-surface-100 text-surface-400 dark:bg-surface-700'
            "
          >
            <i class="pi pi-puzzle text-base" />
          </div>
          <div class="min-w-0">
            <p class="font-medium leading-tight">{{ item.name }}</p>
            <p v-if="item.description" class="mt-0.5 text-xs text-gray-400">
              {{ item.description }}
            </p>
            <span
              v-if="badgeLabel(item)"
              class="mt-1 inline-block rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
            >
              {{ badgeLabel(item) }}
            </span>
          </div>
        </div>
        <ToggleSwitch
          :model-value="item.isEnabled"
          :disabled="isToggleDisabled(item)"
          class="ml-3 shrink-0"
          @update:model-value="(v: boolean) => toggle(item, v)"
        />
      </div>
    </div>

    <ModuleSettingsGuideModal v-model:visible="showGuide" />
  </div>
</template>
