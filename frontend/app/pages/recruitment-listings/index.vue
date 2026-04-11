<script setup lang="ts">
import type {
  RecruitmentCategoryResponse,
  RecruitmentListingSummaryResponse,
  RecruitmentSearchParams,
} from '~/types/recruitment'

const { t } = useI18n()
const api = useRecruitmentApi()

// カテゴリ一覧
const categories = ref<RecruitmentCategoryResponse[]>([])

// フィルター状態
const selectedCategoryId = ref<number | undefined>()
const keyword = ref('')
const location = ref('')
const participationType = ref<string | undefined>()
const startFromDate = ref<Date | undefined>()
const startToDate = ref<Date | undefined>()

// 検索結果
const listings = ref<RecruitmentListingSummaryResponse[]>([])
const loading = ref(false)
const totalCount = ref(0)
const currentPage = ref(0)
const pageSize = 20
const totalPages = ref(0)

// DateオブジェクトをISO8601文字列（yyyy-MM-dd）に変換
function toIsoDateString(d: Date | undefined): string | undefined {
  if (!d) return undefined
  if (typeof d === 'string') return d
  return d.toISOString().slice(0, 10)
}

// カテゴリ読み込み
async function loadCategories() {
  try {
    const res = await api.listCategories()
    categories.value = res.data
  }
  catch {
    // カテゴリ取得失敗は無視（検索は続行できる）
  }
}

// 検索実行
async function search(page = 0) {
  loading.value = true
  currentPage.value = page
  try {
    const params: RecruitmentSearchParams = {
      categoryId: selectedCategoryId.value,
      keyword: keyword.value || undefined,
      location: location.value || undefined,
      participationType: participationType.value,
      startFrom: toIsoDateString(startFromDate.value),
      startTo: toIsoDateString(startToDate.value),
      page,
      size: pageSize,
    }
    const res = await api.searchListings(params)
    listings.value = res.data
    totalCount.value = res.meta.totalElements
    totalPages.value = res.meta.totalPages
  }
  catch {
    listings.value = []
    totalCount.value = 0
    totalPages.value = 0
  }
  finally {
    loading.value = false
  }
}

function selectCategory(id: number | undefined) {
  selectedCategoryId.value = id
  search(0)
}

function resetFilters() {
  keyword.value = ''
  location.value = ''
  participationType.value = undefined
  startFromDate.value = undefined
  startToDate.value = undefined
  selectedCategoryId.value = undefined
  search(0)
}

function formatDate(iso: string) {
  return iso.substring(0, 10)
}

function remainingCount(listing: RecruitmentListingSummaryResponse) {
  return listing.capacity - listing.confirmedCount
}

onMounted(async () => {
  await loadCategories()
  await search(0)
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <!-- ヘッダー -->
    <div class="mb-6">
      <h1 class="text-2xl font-bold">
        {{ $t('recruitment.search.pageTitle') }}
      </h1>
    </div>

    <!-- カテゴリタブ -->
    <div class="mb-4 flex flex-wrap gap-2">
      <Button
        :label="$t('recruitment.search.allCategories')"
        :severity="selectedCategoryId === undefined ? 'primary' : 'secondary'"
        size="small"
        @click="selectCategory(undefined)"
      />
      <Button
        v-for="cat in categories"
        :key="cat.id"
        :label="$t(cat.nameI18nKey)"
        :severity="selectedCategoryId === cat.id ? 'primary' : 'secondary'"
        size="small"
        @click="selectCategory(cat.id)"
      />
    </div>

    <!-- フィルターパネル -->
    <div class="mb-6 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('recruitment.search.keyword') }}</label>
          <InputText
            v-model="keyword"
            :placeholder="$t('recruitment.search.keywordPlaceholder')"
            class="w-full"
            @keyup.enter="search(0)"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('recruitment.search.location') }}</label>
          <InputText
            v-model="location"
            :placeholder="$t('recruitment.search.locationPlaceholder')"
            class="w-full"
            @keyup.enter="search(0)"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('recruitment.search.participationType') }}</label>
          <Select
            v-model="participationType"
            :options="[
              { label: $t('recruitment.search.allTypes'), value: undefined },
              { label: $t('recruitment.participationType.individual'), value: 'INDIVIDUAL' },
              { label: $t('recruitment.participationType.team'), value: 'TEAM' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('recruitment.search.startFrom') }}</label>
          <DatePicker
            v-model="startFromDate"
            date-format="yy-mm-dd"
            show-icon
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('recruitment.search.startTo') }}</label>
          <DatePicker
            v-model="startToDate"
            date-format="yy-mm-dd"
            show-icon
            class="w-full"
          />
        </div>
      </div>
      <div class="mt-3 flex gap-2">
        <Button
          :label="$t('recruitment.search.searchButton')"
          icon="pi pi-search"
          @click="search(0)"
        />
        <Button
          :label="$t('recruitment.search.resetButton')"
          icon="pi pi-refresh"
          severity="secondary"
          @click="resetFilters"
        />
      </div>
    </div>

    <!-- 結果件数 -->
    <p
      v-if="!loading"
      class="mb-4 text-sm text-surface-500"
    >
      {{ $t('recruitment.search.resultsCount', { count: totalCount }) }}
    </p>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 検索結果 -->
    <div
      v-else-if="listings.length > 0"
      class="space-y-3"
    >
      <div
        v-for="listing in listings"
        :key="listing.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-800"
      >
        <NuxtLink
          :to="`/recruitment-listings/${listing.id}`"
          class="block"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0 flex-1">
              <p class="truncate text-base font-semibold">
                {{ listing.title }}
              </p>
              <p class="mt-1 text-sm text-surface-500">
                {{ formatDate(listing.startAt) }}
                <span
                  v-if="listing.location"
                  class="ml-2"
                >
                  <i class="pi pi-map-marker text-xs" /> {{ listing.location }}
                </span>
              </p>
            </div>
            <div class="flex shrink-0 flex-col items-end gap-1">
              <Badge
                v-if="listing.status === 'OPEN'"
                :value="$t('recruitment.status.open')"
                severity="success"
              />
              <Badge
                v-else-if="listing.status === 'FULL'"
                :value="$t('recruitment.status.full')"
                severity="warn"
              />
              <span class="text-sm font-medium">
                <template v-if="listing.paymentEnabled && listing.price">
                  {{ $t('recruitment.search.priceLabel', { price: listing.price.toLocaleString() }) }}
                </template>
                <template v-else>
                  {{ $t('recruitment.search.free') }}
                </template>
              </span>
            </div>
          </div>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <Badge
              :value="listing.participationType === 'INDIVIDUAL'
                ? $t('recruitment.participationType.individual')
                : $t('recruitment.participationType.team')"
              severity="secondary"
              class="text-xs"
            />
            <span class="text-xs text-surface-500">
              {{ $t('recruitment.search.capacity') }}: {{ listing.confirmedCount }}/{{ listing.capacity }}
              <span
                v-if="remainingCount(listing) > 0"
                class="ml-1 text-green-600 dark:text-green-400"
              >
                ({{ $t('recruitment.search.remaining', { count: remainingCount(listing) }) }})
              </span>
            </span>
          </div>
        </NuxtLink>
      </div>
    </div>

    <!-- 結果なし -->
    <div
      v-else
      class="py-12 text-center text-surface-500"
    >
      <i class="pi pi-search mb-2 text-4xl" />
      <p>{{ $t('recruitment.search.noResults') }}</p>
    </div>

    <!-- ページネーション -->
    <div
      v-if="totalPages > 1"
      class="mt-6 flex justify-center"
    >
      <Paginator
        :rows="pageSize"
        :total-records="totalCount"
        :first="currentPage * pageSize"
        @page="(e: { page: number }) => search(e.page)"
      />
    </div>
  </div>
</template>
