<script setup lang="ts">
import type { ModuleCatalog, ModuleCatalogItem } from '~/composables/useAdminDashboardApi'
import type { StorageScopeUsage } from '~/types/storage'
import { formatBytes } from '~/utils/formatBytes'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  slug: string
}>()

const { t } = useI18n()
const moduleApi = useAdminDashboardApi()
const storageApi = useStorageUsageApi()

const catalog = ref<ModuleCatalog | null>(null)
const storage = ref<StorageScopeUsage | null>(null)
const loading = ref(true)
const loadError = ref(false)
let loadRequestId = 0

const scopeKey = computed(() => props.scopeType === 'team' ? 'team' : 'organization')
const planUrl = computed(() => `/billing/plans?scope=${props.scopeType}&slug=${encodeURIComponent(props.slug)}`)
const modules = computed<ModuleCatalogItem[]>(() => catalog.value?.modules ?? [])

async function loadGuide(): Promise<void> {
  const requestId = ++loadRequestId
  const requestedScopeType = props.scopeType
  const requestedSlug = props.slug
  loading.value = true
  loadError.value = false
  catalog.value = null
  storage.value = null

  try {
    // この API は対象スコープの MEMBER 以上だけが取得できる。直リンク時の閲覧ゲートも兼ねる。
    const result = await moduleApi.getModuleCatalog(requestedScopeType, requestedSlug)
    if (requestId !== loadRequestId) return
    catalog.value = result
  }
  catch {
    if (requestId === loadRequestId) {
      loadError.value = true
      loading.value = false
    }
    return
  }

  try {
    const usages = await storageApi.getMyStorageUsage()
    if (requestId === loadRequestId) {
      storage.value = usages.find(usage =>
        usage.scopeType === (requestedScopeType === 'team' ? 'TEAM' : 'ORGANIZATION')
        && usage.slug === requestedSlug,
      ) ?? null
    }
  }
  catch {
    // 機能案内は表示し、容量は専用画面への導線で確認できるようにする。
  }
  finally {
    if (requestId === loadRequestId) loading.value = false
  }
}

watch(() => [props.scopeType, props.slug] as const, loadGuide)
onMounted(loadGuide)
</script>

<template>
  <div class="mt-4 space-y-4" data-testid="scope-guide">
    <PageHeader :title="t(`scopeGuide.${scopeKey}.title`)">
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t(`scopeGuide.${scopeKey}.intro`) }}
      </p>
    </PageHeader>

    <PageLoading v-if="loading" />
    <Message v-else-if="loadError" severity="error" :closable="false" data-testid="scope-guide-load-error">
      {{ t('scopeGuide.loadError') }}
    </Message>
    <template v-else-if="catalog">
      <SectionCard :title="t(`scopeGuide.${scopeKey}.featuresTitle`)">
        <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
          {{ t(`scopeGuide.${scopeKey}.featuresDescription`) }}
        </p>
        <div v-if="modules.length" class="grid gap-3 md:grid-cols-2">
          <div
            v-for="module in modules"
            :key="module.moduleId"
            class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
          >
            <div class="flex flex-wrap items-center gap-2">
              <h3 class="font-semibold">{{ module.name }}</h3>
              <Tag
                :severity="module.isEnabled ? 'success' : 'secondary'"
                :value="t(module.isEnabled ? 'scopeGuide.enabled' : 'scopeGuide.disabled')"
              />
              <Tag
                v-if="module.requiresPaidPlan"
                severity="warn"
                :value="t('scopeGuide.paidRequired')"
              />
              <Tag
                v-if="module.levelAvailable === false"
                severity="secondary"
                :value="t('scopeGuide.unavailable')"
              />
            </div>
            <p v-if="module.description" class="mt-2 text-sm text-surface-600 dark:text-surface-300">
              {{ module.description }}
            </p>
          </div>
        </div>
        <p v-else class="text-sm text-surface-600 dark:text-surface-300">
          {{ t('scopeGuide.noOptionalModules') }}
        </p>
      </SectionCard>

      <SectionCard :title="t('scopeGuide.limitsTitle')">
        <dl class="space-y-3 text-sm">
          <div>
            <dt class="font-semibold">{{ t('scopeGuide.optionalLimitQuestion') }}</dt>
            <dd class="mt-1 text-surface-600 dark:text-surface-300">
              <span v-if="catalog.planLimit != null">
                {{ t('scopeGuide.optionalLimitAnswer', {
                  count: catalog.planLimit,
                  enabled: catalog.enabledCount ?? 0,
                }) }}
              </span>
              <span v-else>{{ t('scopeGuide.optionalLimitUnknown') }}</span>
            </dd>
          </div>
          <div>
            <dt class="font-semibold">{{ t('scopeGuide.paidQuestion') }}</dt>
            <dd class="mt-1 text-surface-600 dark:text-surface-300">
              {{ t('scopeGuide.paidAnswer') }}
              <NuxtLink :to="planUrl" class="ml-1 text-primary underline">
                {{ t('scopeGuide.viewPlans') }}
              </NuxtLink>
            </dd>
          </div>
          <div>
            <dt class="font-semibold">{{ t('scopeGuide.storageQuestion') }}</dt>
            <dd class="mt-1 text-surface-600 dark:text-surface-300">
              <span v-if="storage">
                {{ t('scopeGuide.storageUsage', {
                  used: formatBytes(storage.usedBytes),
                  included: formatBytes(storage.includedBytes),
                }) }}
                <span v-if="storage.maxBytes != null" class="ml-1">
                  {{ t('scopeGuide.storageMax', { max: formatBytes(storage.maxBytes) }) }}
                </span>
              </span>
              <span v-else>{{ t('scopeGuide.storageDynamic') }}</span>
              <NuxtLink to="/settings/storage" class="ml-1 text-primary underline">
                {{ t('scopeGuide.viewStorage') }}
              </NuxtLink>
            </dd>
          </div>
        </dl>
      </SectionCard>

      <SectionCard :title="t(`scopeGuide.${scopeKey}.qaTitle`)">
        <dl class="space-y-4 text-sm">
          <div v-for="index in 3" :key="index">
            <dt class="font-semibold">{{ t(`scopeGuide.${scopeKey}.qa${index}Question`) }}</dt>
            <dd class="mt-1 text-surface-600 dark:text-surface-300">
              {{ t(`scopeGuide.${scopeKey}.qa${index}Answer`) }}
            </dd>
          </div>
        </dl>
      </SectionCard>
    </template>
  </div>
</template>
