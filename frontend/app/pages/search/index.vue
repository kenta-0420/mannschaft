<script setup lang="ts">
import type { GlobalSearchType, GlobalSearchResult, SearchResponse } from '~/types/search'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const searchApi = useSearchApi()
const errorHandler = useErrorHandler()
const query = ref((route.query.q as string) ?? '')
const response = ref<SearchResponse['data'] | null>(null)
const activeType = ref<GlobalSearchType | 'ALL'>('ALL')
const loading = ref(false)
const error = ref<unknown>(null)

const kinds: GlobalSearchType[] = ['schedules', 'events', 'reservations', 'shifts', 'safetyChecks', 'queues', 'teams', 'organizations', 'users']
const groups = computed(() => kinds.filter(kind => activeType.value === 'ALL' || activeType.value === kind))
const totalCount = computed(() => Object.values(response.value?.counts ?? {}).reduce((sum, count) => sum + count, 0))
const shownCount = computed(() => groups.value.reduce((sum, kind) => sum + (response.value?.results[kind].length ?? 0), 0))

async function performSearch() {
  if (query.value.length < 2) return
  loading.value = true
  error.value = null
  try {
    response.value = (await searchApi.search({ q: query.value })).data
    await router.replace({ query: { q: query.value } })
  } catch (failure) {
    error.value = failure
    errorHandler.handleApiError(failure, 'global-search')
  } finally {
    loading.value = false
  }
}

function resultLabel(result: GlobalSearchResult): string {
  return result.title || result.purpose || result.name || result.fullName
    || [result.ticketNumber, result.guestName].filter(Boolean).join(' ')
    || t('globalSearch.resultId', { id: result.id })
}

onMounted(() => {
  if (query.value) performSearch()
})
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader :title="t('globalSearch.title')" />
    <form class="mb-6 flex gap-2" @submit.prevent="performSearch">
      <InputText
        v-model="query"
        class="min-h-11 min-w-0 flex-1 text-base"
        :placeholder="t('globalSearch.placeholder')"
        :aria-label="t('globalSearch.title')"
      />
      <Button type="submit" :label="t('button.search')" icon="pi pi-search" :loading="loading" class="min-h-11" />
    </form>

    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="performSearch" />
    <template v-else-if="response">
      <div class="mb-4 flex flex-wrap gap-2">
        <Button
          :label="`${t('globalSearch.all')} (${totalCount})`"
          :outlined="activeType !== 'ALL'"
          :aria-pressed="activeType === 'ALL'"
          class="min-h-11"
          @click="activeType = 'ALL'"
        />
        <Button
          v-for="kind in kinds"
          :key="kind"
          :label="`${t(`globalSearch.kinds.${kind}`)} (${response.counts[kind]})`"
          :outlined="activeType !== kind"
          :aria-pressed="activeType === kind"
          class="min-h-11"
          @click="activeType = kind"
        />
      </div>
      <DashboardEmptyState v-if="shownCount === 0" icon="pi pi-search" :message="t('globalSearch.empty')" />
      <div v-else class="space-y-4">
        <template v-for="kind in groups" :key="kind">
          <SectionCard v-if="response.results[kind].length" :title="t(`globalSearch.kinds.${kind}`)">
            <p class="mb-3 text-sm text-surface-500">
              {{ t('globalSearch.showing', { shown: response.results[kind].length, total: response.counts[kind] }) }}
            </p>
            <ul class="divide-y divide-surface-200 dark:divide-surface-700">
              <li v-for="result in response.results[kind]" :key="result.id" class="break-words py-3">
                {{ resultLabel(result) }}
              </li>
            </ul>
          </SectionCard>
        </template>
      </div>
    </template>
  </div>
</template>
