<script setup lang="ts">
/**
 * F22.1 市（Market）— 市一覧・検索ページ
 *
 * - 未ログイン公開（middleware:'auth' なし・permitAll）
 * - フィルタ: 都道府県 → 市区町村（連動）/ ジャンル / 札主 / キーワード / 並び順
 * - 条件編集では一覧を更新せず、検索ボタンでまとめて適用
 * - フィルタ状態を URL クエリに同期（共有・ブックマーク可能）
 * - 空状態: ログイン済み権限あり / 権限なし / 未ログイン の3パターン
 * - レスポンシブ: モバイルはフィルタ折りたたみ
 *
 * 設計書: docs/features/F22.1_market/03_ui_i18n.md §2
 * API:    GET /api/v1/public/market/listings
 */
import type {
  MarketListingRegion,
  MarketListingResponse,
  MarketListingSort,
  MarketOwnerType,
} from '~/types/market'
import type { RecruitmentCategoryResponse } from '~/types/recruitment'

definePageMeta({
  layout: 'default',
})

const { t, locale } = useI18n()
const route = useRoute()
const marketApi = useMarketApi()
const { handleApiError } = useErrorHandler()
const authStore = useAuthStore()

const showGuide = ref(false)

const {
  prefectures,
  cities,
  citiesLoading,
  selectedPrefecture,
  selectedCity,
  loadPrefectures,
  selectPrefecture,
} = useMarketRegions()

// =====================================================================
// State
// =====================================================================

const listings = ref<MarketListingResponse[]>([])
const initialLoading = ref(true)
const searching = ref(false)
const totalRecords = ref(0)
const currentPage = ref(0)
const pageSize = 20
let listingRequestSequence = 0

const categories = ref<RecruitmentCategoryResponse[]>([])
const selectedCategoryId = ref<number | null>(null)
const selectedOwnerType = ref<MarketOwnerType | null>(null)
const searchKeyword = ref<string>('')
const selectedSort = ref<MarketListingSort>('START_AT_ASC')

interface MarketFilterConditions {
  prefecture: string | null
  city: string | null
  categoryId: number | null
  ownerType: MarketOwnerType | null
  keyword: string
  sort: MarketListingSort
}

const appliedFilters = ref<MarketFilterConditions>({
  prefecture: null,
  city: null,
  categoryId: null,
  ownerType: null,
  keyword: '',
  sort: 'START_AT_ASC',
})

const categoryOptions = computed(() => categories.value.map(category => ({
  id: category.id,
  label: t(category.nameI18nKey),
})))

const sortOptions = computed(() => [
  { value: 'START_AT_ASC' as const, label: t('market.sort.startAtAsc') },
  { value: 'DEADLINE_ASC' as const, label: t('market.sort.deadlineAsc') },
  { value: 'DEADLINE_DESC' as const, label: t('market.sort.deadlineDesc') },
])

/** モバイルフィルタ折りたたみ */
const filterExpanded = ref(false)

// =====================================================================
// URL クエリ同期
// =====================================================================

function isMarketListingSort(value: unknown): value is MarketListingSort {
  return value === 'START_AT_ASC' || value === 'DEADLINE_ASC' || value === 'DEADLINE_DESC'
}

function draftFilters(): MarketFilterConditions {
  return {
    prefecture: selectedPrefecture.value,
    city: selectedCity.value,
    categoryId: selectedCategoryId.value,
    ownerType: selectedOwnerType.value,
    keyword: searchKeyword.value.trim(),
    sort: selectedSort.value,
  }
}

// 初回マウント時に URL クエリから下書き条件と適用済み条件を復元する
async function syncFromQuery() {
  const q = route.query
  if (typeof q.prefecture === 'string' && q.prefecture) {
    await selectPrefecture(q.prefecture)
    if (typeof q.city === 'string' && q.city) {
      selectedCity.value = q.city
    }
  }
  if (typeof q.category === 'string' && q.category) {
    selectedCategoryId.value = Number(q.category) || null
  }
  if (q.owner === 'PERSONAL' || q.owner === 'TEAM' || q.owner === 'ORGANIZATION') {
    selectedOwnerType.value = q.owner
  }
  if (typeof q.keyword === 'string') {
    searchKeyword.value = q.keyword
  }
  if (isMarketListingSort(q.sort)) {
    selectedSort.value = q.sort
  }
  if (typeof q.page === 'string') {
    const page = Number(q.page)
    currentPage.value = Number.isInteger(page) && page > 0 ? page : 0
  }
  appliedFilters.value = draftFilters()
}

function buildQuery(filters: MarketFilterConditions, page: number): Record<string, string> {
  const q: Record<string, string> = {}
  if (filters.prefecture) q.prefecture = filters.prefecture
  if (filters.city) q.city = filters.city
  if (filters.categoryId != null) q.category = String(filters.categoryId)
  if (filters.ownerType) q.owner = filters.ownerType
  if (filters.keyword) q.keyword = filters.keyword
  if (filters.sort !== 'START_AT_ASC') q.sort = filters.sort
  if (page > 0) q.page = String(page)
  return q
}

async function replaceQuery() {
  await navigateTo(
    { query: buildQuery(appliedFilters.value, currentPage.value) },
    { replace: true },
  )
}

// =====================================================================
// Fetch
// =====================================================================

async function fetchListings(isInitial = false) {
  const sequence = ++listingRequestSequence
  const filters = { ...appliedFilters.value }
  const requestedPage = currentPage.value
  const requestedLocale = locale.value
  if (isInitial) initialLoading.value = true
  else searching.value = true

  try {
    const res = await marketApi.listMarketListings({
      prefecture: filters.prefecture ?? undefined,
      city: filters.city ?? undefined,
      categoryId: filters.categoryId ?? undefined,
      ownerType: filters.ownerType ?? undefined,
      keyword: filters.keyword || undefined,
      includeRegionNone: filters.prefecture == null && filters.city == null,
      sort: filters.sort,
      page: requestedPage,
      size: pageSize,
      lang: requestedLocale,
    })
    if (sequence !== listingRequestSequence) return
    listings.value = res.data
    totalRecords.value = res.meta.total
  }
  catch (error) {
    if (sequence !== listingRequestSequence) return
    handleApiError(error, t('market.error.loadFailed'))
  }
  finally {
    if (sequence === listingRequestSequence) {
      initialLoading.value = false
      searching.value = false
    }
  }
}

async function fetchCategories() {
  try {
    // 市は未ログイン公開ページ。認証必須の recruitment-categories を直叩きすると
    // 未ログインで 401 → useApi が市ページごと /login へ飛ばす。公開APIのみに依存させる。
    const res = await marketApi.listMarketCategories()
    categories.value = res.data
  }
  catch {
    // カテゴリ取得失敗は非致命的（フィルタが空になるだけ）
  }
}

// =====================================================================
// フィルタ変更ハンドラ
// =====================================================================

async function onPrefectureChange(code: string | null) {
  await selectPrefecture(code)
}

async function applyFilters() {
  currentPage.value = 0
  appliedFilters.value = draftFilters()
  await replaceQuery()
  await fetchListings()
}

async function applyPrefectureFilter(code: string | null) {
  await selectPrefecture(code)
  await applyFilters()
}

// ロケール切替時に、札の地域名表示を現在ロケールへ追従させる（都道府県/市区町村フィルタは
// useMarketRegions 側の watch が再取得する）。
watch(locale, async () => {
  await fetchListings()
})

// =====================================================================
// Paginator
// =====================================================================

async function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  await replaceQuery()
  await fetchListings()
}

// =====================================================================
// 空状態の判定
// =====================================================================

const isAuthenticated = computed(() => authStore.isAuthenticated)

// =====================================================================
// カードヘルパー
// =====================================================================

function statusSeverity(status: string): 'success' | 'warn' | 'secondary' | 'danger' {
  switch (status) {
    case 'OPEN': return 'success'
    case 'FULL': return 'warn'
    case 'COMPLETED': return 'secondary'
    default: return 'danger'
  }
}

const { formatDate: formatDeadline } = useDatetime()

/**
 * 複数地域募集（F22.1 Phase2 D）の表示用地域配列を返す。
 * regions[] を優先し、空なら後方互換の単一 region をフォールバックに使う。
 */
function regionTags(listing: MarketListingResponse): MarketListingRegion[] {
  if (listing.regions && listing.regions.length > 0) {
    return listing.regions
  }
  return listing.region ? [listing.region] : []
}

/** 地域 1 件を「都道府県 市区町村」形式に整形する（市区町村が空なら県のみ）。 */
function regionLabel(region: MarketListingRegion): string {
  return region.cityName
    ? `${region.prefectureName} ${region.cityName}`
    : region.prefectureName
}

// =====================================================================
// Init
// =====================================================================

onMounted(async () => {
  await Promise.all([loadPrefectures(), fetchCategories()])
  await syncFromQuery()
  await fetchListings(true)
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6" data-testid="market-page">
    <!-- ヘッダー -->
    <PageHeader :title="$t('market.title')" help @help="showGuide = true">
      <template #actions>
        <!-- 個人名義の作成フォームへ直接つなぐ。チーム・組織は各管理市から主体を保持する。 -->
        <Button
          :label="$t('market.action.post')"
          icon="pi pi-tag"
          severity="secondary"
          outlined
          data-testid="market-post-link"
          @click="navigateTo({ path: '/me/market', query: { create: 'true' } })"
        />
      </template>
    </PageHeader>

    <!-- パンくず -->
    <nav class="mb-4 flex items-center gap-1 text-sm text-surface-500" aria-label="breadcrumb">
      <button
        class="hover:text-primary cursor-pointer"
        :class="{ 'text-primary font-semibold': !appliedFilters.prefecture }"
        @click="applyPrefectureFilter(null)"
      >
        {{ $t('market.breadcrumb.national') }}
      </button>
      <template v-if="appliedFilters.prefecture">
        <i class="pi pi-chevron-right text-xs" />
        <button
          class="hover:text-primary cursor-pointer"
          :class="{ 'text-primary font-semibold': !appliedFilters.city }"
          @click="applyPrefectureFilter(appliedFilters.prefecture)"
        >
          {{ prefectures.find(p => p.code === appliedFilters.prefecture)?.name ?? appliedFilters.prefecture }}
        </button>
      </template>
      <template v-if="appliedFilters.city">
        <i class="pi pi-chevron-right text-xs" />
        <span class="font-semibold text-surface-700">
          {{ cities.find(c => c.code === appliedFilters.city)?.name ?? appliedFilters.city }}
        </span>
      </template>
      <span
        v-if="totalRecords > 0"
        class="ml-1 rounded-full bg-surface-200 px-2 py-0.5 text-xs font-medium text-surface-600"
      >
        {{ $t('market.breadcrumb.count', { count: totalRecords }) }}
      </span>
    </nav>

    <!-- フィルタバー（デスクトップは横並び・モバイルは折りたたみ） -->
    <div class="mb-6">
      <!-- モバイル折りたたみトグル -->
      <div class="mb-2 sm:hidden">
        <Button
          :label="$t('market.filter.mobileToggle')"
          icon="pi pi-filter"
          severity="secondary"
          size="small"
          outlined
          @click="filterExpanded = !filterExpanded"
        />
      </div>

      <form
        class="flex-wrap items-start gap-3"
        :class="filterExpanded ? 'flex' : 'hidden sm:flex'"
        data-testid="market-filter-bar"
        @submit.prevent="applyFilters"
      >
        <!-- 都道府県 -->
        <Select
          v-model="selectedPrefecture"
          :options="prefectures"
          option-label="name"
          option-value="code"
          :placeholder="$t('market.filter.allPrefectures')"
          show-clear
          class="w-44 field-bordered"
          :aria-label="$t('market.filter.prefecture')"
          data-testid="market-prefecture-select"
          @change="(e: { value: string | null }) => onPrefectureChange(e.value)"
        />

        <!-- 市区町村（都道府県選択後に有効化） -->
        <Select
          v-model="selectedCity"
          :options="cities"
          option-label="name"
          option-value="code"
          :placeholder="$t('market.filter.allCities')"
          show-clear
          :disabled="!selectedPrefecture || citiesLoading"
          :loading="citiesLoading"
          class="w-48 field-bordered"
          :aria-label="$t('market.filter.city')"
          data-testid="market-city-select"
        />

        <!-- ジャンル -->
        <Select
          v-model="selectedCategoryId"
          :options="categoryOptions"
          option-label="label"
          option-value="id"
          :placeholder="$t('market.filter.allCategories')"
          show-clear
          class="w-44 field-bordered"
          :aria-label="$t('market.filter.category')"
          data-testid="market-category-select"
        />

        <!-- 札主区分 -->
        <Select
          v-model="selectedOwnerType"
          :options="[
            { value: 'PERSONAL', label: $t('market.ownerType.personal') },
            { value: 'TEAM', label: $t('market.ownerType.team') },
            { value: 'ORGANIZATION', label: $t('market.ownerType.organization') },
          ]"
          option-label="label"
          option-value="value"
          :placeholder="$t('market.filter.allOwnerTypes')"
          show-clear
          class="w-44 field-bordered"
          :aria-label="$t('market.filter.ownerType')"
          data-testid="market-owner-type-select"
        />

        <!-- キーワード -->
        <IconField class="flex-1 min-w-[200px]">
          <InputIcon class="pi pi-search" />
          <InputText
            v-model="searchKeyword"
            :placeholder="$t('market.filter.keyword')"
            class="w-full field-bordered"
            :aria-label="$t('market.filter.keyword')"
            data-testid="market-keyword-input"
          />
        </IconField>

        <!-- 並び順 -->
        <Select
          v-model="selectedSort"
          :options="sortOptions"
          option-label="label"
          option-value="value"
          :placeholder="$t('market.sort.label')"
          class="w-44 field-bordered"
          :aria-label="$t('market.sort.label')"
          data-testid="market-sort-select"
        />

        <Button
          type="submit"
          :label="$t('market.filter.search')"
          icon="pi pi-search"
          :loading="searching"
          data-testid="market-search-button"
        />

        <!-- 件数表示 -->
        <div
          v-if="!initialLoading && totalRecords > 0"
          class="flex items-center text-sm text-surface-500"
        >
          {{ $t('market.listing.count', { count: totalRecords }) }}
        </div>
      </form>
    </div>

    <!-- ローディング -->
    <PageLoading v-if="initialLoading" />

    <!-- 空状態（立場別3パターン） -->
    <template v-else-if="listings.length === 0">
      <!-- ログイン済み・権限ありを確認する方法がないためシンプルに2パターンで分岐 -->
      <div class="flex flex-col items-center gap-3" data-testid="market-empty-state">
        <DashboardEmptyState
          icon="pi pi-tag"
          :message="$t('market.empty.title')"
        />
        <!-- 未ログイン -->
        <template v-if="!isAuthenticated">
          <Button
            :label="$t('market.action.loginToApply')"
            icon="pi pi-sign-in"
            @click="navigateTo('/login')"
          />
        </template>
        <!-- ログイン済み -->
        <template v-else>
          <Button
            :label="$t('market.action.post')"
            icon="pi pi-plus"
            severity="secondary"
            @click="navigateTo({ path: '/me/market', query: { create: 'true' } })"
          />
        </template>
      </div>
    </template>

    <!-- 札カード一覧 -->
    <template v-else>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" data-testid="market-listing-grid">
        <div
          v-for="listing in listings"
          :key="listing.id"
          class="cursor-pointer rounded-lg border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-900"
          role="article"
          :aria-label="listing.title"
          :data-testid="`market-listing-card-${listing.id}`"
          tabindex="0"
          @click="navigateTo(`/market/listings/${listing.id}`)"
          @keydown.enter="navigateTo(`/market/listings/${listing.id}`)"
        >
          <!-- 主催（チーム公称名 + アイコン） -->
          <div class="mb-2 flex items-center gap-2">
            <Avatar
              v-if="listing.owner.iconUrl"
              :image="listing.owner.iconUrl"
              shape="circle"
              size="normal"
            />
            <Avatar
              v-else
              :label="listing.owner.displayName.charAt(0) || 'T'"
              shape="circle"
              size="normal"
            />
            <span class="truncate text-sm font-medium text-surface-600">
              {{ listing.owner.displayName }}
            </span>
          </div>

          <!-- タイトル -->
          <h3 class="mb-2 line-clamp-2 font-semibold text-surface-800 dark:text-surface-100">
            {{ listing.title }}
          </h3>

          <!-- カテゴリ / 地域 -->
          <div class="mb-2 flex flex-wrap gap-1">
            <Tag
              :value="$t(listing.category.nameKey)"
              severity="info"
              class="text-xs"
            />
            <Tag
              v-for="region in regionTags(listing)"
              :key="`${region.prefectureCode}-${region.cityCode ?? ''}`"
              :value="regionLabel(region)"
              severity="secondary"
              class="text-xs"
            />
            <Tag
              v-if="regionTags(listing).length === 0"
              :value="$t('market.card.regionNone')"
              severity="secondary"
              class="text-xs"
            />
          </div>

          <!-- 締切 / 定員 / ステータス -->
          <div class="flex items-center justify-between text-xs text-surface-500">
            <span>
              <i class="pi pi-calendar mr-1" />
              {{ formatDeadline(listing.applicationDeadline) }}
            </span>
            <span>
              <i class="pi pi-users mr-1" />
              {{ $t('market.card.capacity', { confirmed: listing.confirmedCount, capacity: listing.capacity }) }}
            </span>
          </div>
          <div class="mt-2 flex justify-end">
            <Tag
              :value="$t(`market.status.${listing.status}`)"
              :severity="statusSeverity(listing.status)"
              class="text-xs"
            />
          </div>
        </div>
      </div>

      <!-- ページネーション -->
      <div class="mt-6">
        <Paginator
          :rows="pageSize"
          :total-records="totalRecords"
          :first="currentPage * pageSize"
          @page="onPageChange"
        />
      </div>
    </template>

    <MarketGuideModal v-model:visible="showGuide" />
  </div>
</template>
