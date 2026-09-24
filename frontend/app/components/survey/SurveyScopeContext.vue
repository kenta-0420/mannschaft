<script setup lang="ts">
import type { SurveyManagementContext } from '~/utils/surveyScopeContext'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeName: string | null
  loading: boolean
  managementContext: SurveyManagementContext | null
}>()

const { t } = useI18n()

const managementLabel = computed(() => {
  if (!props.managementContext) return null
  return t(`surveys.detail.scopeContext.management.${props.managementContext}`)
})
</script>

<template>
  <aside
    class="mb-4 flex min-w-0 flex-col gap-2 bg-surface-50 px-3 py-2 text-sm dark:bg-surface-800/60 sm:flex-row sm:items-center sm:justify-between"
    :aria-label="t('surveys.detail.scopeContext.label')"
    data-testid="survey-scope-context"
  >
    <div class="flex min-w-0 items-start gap-2">
      <i class="pi pi-sitemap mt-0.5 shrink-0 text-surface-400" aria-hidden="true" />
      <div class="min-w-0">
        <span
          class="text-xs font-medium text-surface-500 dark:text-surface-400"
          data-testid="survey-scope-type"
        >
          {{ t(`surveys.detail.scopeContext.type.${scopeType}`) }}
        </span>
        <p
          class="break-words font-medium text-surface-800 dark:text-surface-100"
          data-testid="survey-scope-name"
        >
          <span v-if="loading">{{ t('surveys.detail.scopeContext.loading') }}</span>
          <span v-else-if="scopeName">{{ scopeName }}</span>
          <span v-else class="text-surface-500 dark:text-surface-400">
            {{ t('surveys.detail.scopeContext.nameUnavailable') }}
          </span>
        </p>
      </div>
    </div>

    <span
      v-if="managementLabel"
      class="self-start rounded-full bg-primary-50 px-2.5 py-1 text-xs font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-200 sm:shrink-0"
      data-testid="survey-management-context"
    >
      {{ managementLabel }}
    </span>
  </aside>
</template>
