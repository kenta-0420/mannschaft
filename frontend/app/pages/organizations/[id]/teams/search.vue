<script setup lang="ts">
/**
 * F15.4 組織内チーム（店舗）検索ページ
 *
 * 設計書: docs/features/F15.4_team_store_search_within_org.md §5
 *
 * - URL クエリパラメータ (`keyword/prefecture/city/template/page/size/sort`) をソース・オブ・トゥルースとし、
 *   ブラウザの戻る / 進むで状態が復元される。
 * - 未ログイン者は `TeamSearchCard` を `compact` モードで描画し、詳細遷移を抑制する。
 * - 認証ミドルウェアは適用しない（公開検索）。
 */
import { useDebounceFn } from '@vueuse/core'
import {
  OrganizationNotFoundError,
  TeamSearchRateLimitError,
  type TeamSearchItem,
  type TeamSearchQuery,
  type TeamSearchSort,
} from '~/types/team-search'
import type { CityResponse, PrefectureResponse } from '~/types/matching'

definePageMeta({
  layout: 'default',
})

useHead({
  meta: [{ name: 'robots', content: 'index,follow' }],
})

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const matchingApi = useMatchingApi()
const authStore = useAuthStore()
const notification = useNotification()

const organizationId = computed<string>(() => {
  const raw = route.params.id
  return String(Array.isArray(raw) ? raw[0] : raw)
})

const isAuthenticated = computed(() => authStore.isAuthenticated)

// === 組織情報 ===
const organizationName = ref<string>('')
async function loadOrganizationName() {
  if (!organizationId.value) return
  try {
    const res = await orgApi.getOrganization(organizationId.value)
    organizationName.value = res.data.basicInfo?.name ?? ''
  } catch {
    // 404 のときは検索 API 側でも 404 が返るため、ここではエラー表示しない
  }
}

// === URL クエリ ⇔ ref 双方向同期 ===
const keyword = ref<string>(stringQuery('keyword'))
const prefectureCode = ref<string>(stringQuery('prefecture'))
const cityCode = ref<string>(stringQuery('city'))
const template = ref<string>(stringQuery('template'))
const sort = ref<TeamSearchSort>(parseSort(stringQuery('sort')))
const page = ref<number>(numberQuery('page', 0))
const size = ref<number>(numberQuery('size', 20))

function stringQuery(key: string): string {
  const v = route.query[key]
  if (typeof v === 'string') return v
  if (Array.isArray(v) && typeof v[0] === 'string') return v[0]
  return ''
}

function numberQuery(key: string, defaultValue: number): number {
  const v = stringQuery(key)
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? n : defaultValue
}

function parseSort(v: string): TeamSearchSort {
  if (v === 'name,asc' || v === 'createdAt,desc' || v === 'nameKana,asc') return v
  return 'nameKana,asc'
}

// === マスターデータ ===
const prefectures = ref<PrefectureResponse[]>([])
const cities = ref<CityResponse[]>([])

async function loadPrefectures() {
  try {
    const res = await matchingApi.getPrefectures()
    prefectures.value = res.data
  } catch {
    // マスタ取得失敗時はプルダウンが空になるだけで検索自体は可
  }
}

async function loadCities(prefCode: string) {
  if (!prefCode) {
    cities.value = []
    return
  }
  try {
    const res = await matchingApi.getCities(prefCode)
    cities.value = res.data
  } catch {
    cities.value = []
  }
}

watch(prefectureCode, async (next, prev) => {
  if (next !== prev) {
    // 都道府県変更時は市町村をリセット
    cityCode.value = ''
    await loadCities(next)
  }
})

// === テンプレートオプション（SearchBar.vue から流用） ===
const templateOptions = computed(() => [
  { label: t('organizationTeamSearch.anyTemplate'), value: '' },
  { label: 'クラブ・サークル', value: 'CLUB' },
  { label: 'クリニック', value: 'CLINIC' },
  { label: 'クラス', value: 'CLASS' },
  { label: 'コミュニティ', value: 'COMMUNITY' },
  { label: '企業', value: 'COMPANY' },
  { label: '家族', value: 'FAMILY' },
  { label: '飲食店', value: 'RESTAURANT' },
  { label: '美容院・サロン', value: 'BEAUTY' },
  { label: '店舗・小売', value: 'STORE' },
  { label: 'ボランティア・NPO', value: 'VOLUNTEER' },
  { label: '自治会', value: 'NEIGHBORHOOD' },
  { label: 'マンション管理組合', value: 'CONDO' },
  { label: 'その他', value: 'OTHER' },
])

// === 検索状態 ===
const items = ref<TeamSearchItem[]>([])
const totalElements = ref<number>(0)
const loading = ref<boolean>(false)
const errorMessage = ref<string | null>(null)

async function executeSearch() {
  if (!organizationId.value) return
  loading.value = true
  errorMessage.value = null
  try {
    // F22.1 Phase2 足場C 第三陣: 旧実装の code→名称変換を廃止し、コードをそのまま送る。
    // BE `OrganizationTeamSearchController` の @RequestParam prefectureCode/cityCode（camelCase）と 1:1。
    // BE 側 dual-support によりコード指定時はコード一致で絞り込まれる。
    const query: TeamSearchQuery = {
      keyword: keyword.value || undefined,
      prefectureCode: prefectureCode.value || undefined,
      cityCode: cityCode.value || undefined,
      template: template.value || undefined,
      page: page.value,
      size: size.value,
      sort: sort.value,
    }
    const res = await teamApi.searchOrganizationTeams(organizationId.value, query)
    items.value = res.data
    totalElements.value = res.meta.totalElements
  } catch (error) {
    items.value = []
    totalElements.value = 0
    if (error instanceof OrganizationNotFoundError) {
      errorMessage.value = t('organizationTeamSearch.organizationNotFound')
    } else if (error instanceof TeamSearchRateLimitError) {
      errorMessage.value = t('organizationTeamSearch.rateLimitExceeded')
    } else {
      errorMessage.value = t('organizationTeamSearch.error')
    }
    notification.error(errorMessage.value)
  } finally {
    loading.value = false
  }
}

// === URL クエリ同期: refs → URL ===
function syncUrl() {
  const next: Record<string, string> = {}
  if (keyword.value) next.keyword = keyword.value
  if (prefectureCode.value) next.prefecture = prefectureCode.value
  if (cityCode.value) next.city = cityCode.value
  if (template.value) next.template = template.value
  if (page.value > 0) next.page = String(page.value)
  if (size.value !== 20) next.size = String(size.value)
  if (sort.value !== 'nameKana,asc') next.sort = sort.value
  router.replace({ path: route.path, query: next })
}

// キーワードはタイプ中 debounce、ページ・ソート・絞り込みは即時反映
const debouncedKeywordSearch = useDebounceFn(() => {
  page.value = 0
  syncUrl()
  executeSearch()
}, 300)

function onKeywordInput() {
  debouncedKeywordSearch()
}

function onFilterChange() {
  page.value = 0
  syncUrl()
  executeSearch()
}

function onSearchClick() {
  page.value = 0
  syncUrl()
  executeSearch()
}

function onReset() {
  keyword.value = ''
  prefectureCode.value = ''
  cityCode.value = ''
  template.value = ''
  sort.value = 'nameKana,asc'
  page.value = 0
  syncUrl()
  executeSearch()
}

function onPageEvent(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  syncUrl()
  executeSearch()
}

// === ブラウザバック対応: route.query の変化を ref に反映 ===
watch(
  () => route.query,
  (q) => {
    const k = typeof q.keyword === 'string' ? q.keyword : ''
    if (k !== keyword.value) keyword.value = k
    const p = typeof q.prefecture === 'string' ? q.prefecture : ''
    if (p !== prefectureCode.value) prefectureCode.value = p
    const c = typeof q.city === 'string' ? q.city : ''
    if (c !== cityCode.value) cityCode.value = c
    const tpl = typeof q.template === 'string' ? q.template : ''
    if (tpl !== template.value) template.value = tpl
    const newPage = numberQuery('page', 0)
    if (newPage !== page.value) page.value = newPage
    const newSize = numberQuery('size', 20)
    if (newSize !== size.value) size.value = newSize
    const newSort = parseSort(typeof q.sort === 'string' ? q.sort : '')
    if (newSort !== sort.value) sort.value = newSort
  },
)

const isEmpty = computed(() => !loading.value && items.value.length === 0 && !errorMessage.value)

onMounted(async () => {
  await loadPrefectures()
  if (prefectureCode.value) await loadCities(prefectureCode.value)
  await loadOrganizationName()
  await executeSearch()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div class="mb-6 flex items-center gap-4">
      <BackButton :to="`/organizations/${organizationId}`" />
      <div class="flex-1">
        <h1 class="text-2xl font-bold">{{ $t('organizationTeamSearch.title') }}</h1>
        <p v-if="organizationName" class="text-sm text-gray-500">{{ organizationName }}</p>
      </div>
    </div>

    <!-- 検索フォーム -->
    <div class="mb-6 rounded-lg border border-surface-200 bg-surface-0 p-4">
      <div class="flex flex-wrap items-end gap-3">
        <div class="min-w-48 flex-1">
          <label class="mb-1 block text-sm font-medium" for="team-search-keyword">
            {{ $t('organizationTeamSearch.keywordPlaceholder') }}
          </label>
          <IconField>
            <InputIcon class="pi pi-search" />
            <InputText
              id="team-search-keyword"
              v-model="keyword"
              type="search"
              :placeholder="$t('organizationTeamSearch.keywordPlaceholder')"
              class="w-full"
              @input="onKeywordInput"
              @keyup.enter="onSearchClick"
            />
          </IconField>
        </div>

        <div class="w-44">
          <label class="mb-1 block text-sm font-medium" for="team-search-prefecture">
            {{ $t('organizationTeamSearch.prefectureLabel') }}
          </label>
          <Select
            id="team-search-prefecture"
            v-model="prefectureCode"
            :options="[{ code: '', name: $t('organizationTeamSearch.anyPrefecture') }, ...prefectures]"
            option-label="name"
            option-value="code"
            class="w-full"
            :aria-label="$t('organizationTeamSearch.prefectureLabel')"
            @update:model-value="onFilterChange"
          />
        </div>

        <div class="w-44">
          <label class="mb-1 block text-sm font-medium" for="team-search-city">
            {{ $t('organizationTeamSearch.cityLabel') }}
          </label>
          <Select
            id="team-search-city"
            v-model="cityCode"
            :options="[{ code: '', name: $t('organizationTeamSearch.anyCity') }, ...cities]"
            option-label="name"
            option-value="code"
            :disabled="!prefectureCode"
            class="w-full"
            :aria-label="$t('organizationTeamSearch.cityLabel')"
            @update:model-value="onFilterChange"
          />
        </div>

        <div class="w-44">
          <label class="mb-1 block text-sm font-medium" for="team-search-template">
            {{ $t('organizationTeamSearch.templateLabel') }}
          </label>
          <Select
            id="team-search-template"
            v-model="template"
            :options="templateOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            :aria-label="$t('organizationTeamSearch.templateLabel')"
            @update:model-value="onFilterChange"
          />
        </div>

        <div class="flex gap-2">
          <Button
            :label="$t('organizationTeamSearch.searchButton')"
            icon="pi pi-search"
            @click="onSearchClick"
          />
          <Button
            :label="$t('organizationTeamSearch.resetButton')"
            icon="pi pi-refresh"
            severity="secondary"
            outlined
            @click="onReset"
          />
        </div>
      </div>
    </div>

    <!-- 結果ヘッダ・ページネーション（上） -->
    <div
      v-if="!loading && !errorMessage && items.length > 0"
      class="mb-4 flex items-center justify-between"
    >
      <span class="text-sm text-gray-600">
        {{ $t('organizationTeamSearch.resultCount', { count: totalElements }) }}
      </span>
      <Paginator
        :rows="size"
        :total-records="totalElements"
        :first="page * size"
        :rows-per-page-options="[10, 20, 50]"
        @page="onPageEvent"
      />
    </div>

    <!-- ローディング: スケルトン × 6 -->
    <div
      v-if="loading"
      class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      data-testid="team-search-loading"
    >
      <div
        v-for="n in 6"
        :key="n"
        class="rounded-lg border-2 border-surface-200 bg-surface-0 p-4"
      >
        <div class="mb-3 flex items-center gap-3">
          <Skeleton shape="circle" size="3rem" />
          <div class="flex-1 space-y-2">
            <Skeleton width="60%" height="1rem" />
            <Skeleton width="40%" height="0.75rem" />
          </div>
        </div>
        <Skeleton width="80%" height="0.75rem" />
      </div>
    </div>

    <!-- エラー -->
    <div
      v-else-if="errorMessage"
      class="rounded-lg border border-red-200 bg-red-50 p-6 text-center"
    >
      <p class="mb-4 text-red-700">{{ errorMessage }}</p>
      <Button
        :label="$t('organizationTeamSearch.retry')"
        icon="pi pi-refresh"
        @click="executeSearch"
      />
    </div>

    <!-- 0 件 -->
    <div v-else-if="isEmpty" class="flex flex-col items-center gap-4 py-8">
      <DashboardEmptyState
        icon="pi pi-search"
        :message="$t('organizationTeamSearch.empty')"
      />
      <Button
        :label="$t('organizationTeamSearch.resetButton')"
        icon="pi pi-refresh"
        severity="secondary"
        outlined
        @click="onReset"
      />
    </div>

    <!-- 結果カードグリッド -->
    <div
      v-else
      class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
    >
      <TeamSearchCard
        v-for="item in items"
        :key="item.id"
        :team="item"
        :compact="!isAuthenticated"
      />
    </div>

    <!-- ページネーション（下） -->
    <div
      v-if="!loading && !errorMessage && items.length > 0"
      class="mt-6 flex justify-end"
    >
      <Paginator
        :rows="size"
        :total-records="totalElements"
        :first="page * size"
        :rows-per-page-options="[10, 20, 50]"
        @page="onPageEvent"
      />
    </div>
  </div>
</template>
