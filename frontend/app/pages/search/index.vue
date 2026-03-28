<script setup lang="ts">
import type { SearchResult, ContentType, SearchResponse } from '~/types/search'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const searchApi = useSearchApi()
const notification = useNotification()

const query = ref((route.query.q as string) ?? '')
const results = ref<SearchResult[]>([])
const typeCounts = ref<Record<string, number>>({})
const timedOutTypes = ref<ContentType[]>([])
const activeType = ref<ContentType | 'ALL'>('ALL')
const loading = ref(false)
const totalPages = ref(0)
const currentPage = ref(0)
const zeroHelp = ref<{ didYouMean: string | null; broaderQuery: string | null } | null>(null)

async function performSearch(page = 0) {
  if (!query.value || query.value.length < 2) return
  loading.value = true
  try {
    const params: Parameters<typeof searchApi.search>[0] = { q: query.value, page, perPage: 20 }
    if (activeType.value !== 'ALL') params.type = activeType.value
    const res: SearchResponse = await searchApi.search(params)
    results.value = res.data.results
    typeCounts.value = res.data.typeCounts
    timedOutTypes.value = res.data.timedOutTypes
    zeroHelp.value = res.data.zeroResultsHelp ?? null
    totalPages.value = res.meta.totalPages ?? 0
    currentPage.value = res.meta.page
    router.replace({ query: { q: query.value } })
  } catch {
    notification.error('検索に失敗しました')
  } finally {
    loading.value = false
  }
}

function handleTypeSelect(type: ContentType | 'ALL') {
  activeType.value = type
  performSearch()
}

function handlePageChange(event: { page: number }) {
  performSearch(event.page)
}

function useSuggestion(text: string) {
  query.value = text
  performSearch()
}

onMounted(() => {
  if (query.value) performSearch()
})
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <h1 class="mb-6 text-2xl font-bold">検索</h1>

    <div class="mb-6 flex gap-2">
      <InputText
        v-model="query"
        class="flex-1"
        placeholder="キーワードで検索（2文字以上）"
        @keyup.enter="performSearch()"
      />
      <Button label="検索" icon="pi pi-search" :loading="loading" @click="performSearch()" />
    </div>

    <div v-if="Object.keys(typeCounts).length > 0" class="mb-4">
      <SearchFilterTabs :type-counts="typeCounts" :active-type="activeType" @select="handleTypeSelect" />
    </div>

    <div v-if="timedOutTypes.length > 0" class="mb-4 rounded-lg border border-yellow-300 bg-yellow-50 p-3 text-sm text-yellow-800 dark:border-yellow-700 dark:bg-yellow-950 dark:text-yellow-200">
      <i class="pi pi-exclamation-triangle mr-1" />
      一部の検索がタイムアウトしました: {{ timedOutTypes.join(', ') }}
    </div>

    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

    <div v-else-if="results.length === 0 && query.length >= 2" class="py-12 text-center">
      <i class="pi pi-search mb-2 text-4xl text-surface-400" />
      <p class="text-surface-500">検索結果がありません</p>
      <div v-if="zeroHelp" class="mt-4 space-y-2">
        <p v-if="zeroHelp.didYouMean" class="text-sm">
          もしかして:
          <button class="text-primary hover:underline" @click="useSuggestion(zeroHelp!.didYouMean!)">
            {{ zeroHelp.didYouMean }}
          </button>
        </p>
        <p v-if="zeroHelp.broaderQuery" class="text-sm">
          より広い検索:
          <button class="text-primary hover:underline" @click="useSuggestion(zeroHelp!.broaderQuery!)">
            {{ zeroHelp.broaderQuery }}
          </button>
        </p>
      </div>
    </div>

    <div v-else class="space-y-3">
      <SearchResultCard v-for="result in results" :key="`${result.type}-${result.id}`" :result="result" />
    </div>

    <div v-if="totalPages > 1" class="mt-6 flex justify-center">
      <Paginator :rows="20" :total-records="totalPages * 20" :first="currentPage * 20" @page="handlePageChange" />
    </div>
  </div>
</template>
