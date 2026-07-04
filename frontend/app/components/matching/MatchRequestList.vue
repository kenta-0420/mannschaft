<script setup lang="ts">
import type {
  CityResponse,
  MatchCategory,
  MatchRequestResponse,
  MatchRequestSearchParams,
  PrefectureResponse,
} from '~/types/matching'

const props = defineProps<{ teamId?: string }>()
const emit = defineEmits<{ select: [req: MatchRequestResponse]; create: [] }>()

const { searchRequests, getTeamRequests, getPrefectures, getCities } = useMatchingApi()
const { t } = useI18n()
const { showError } = useNotification()
const { relativeTime } = useRelativeTime()
const authStore = useAuthStore()
const { saveLast, loadLast, loadHistory, pushHistory } = useMatchingSearchHistory()

const requests = ref<MatchRequestResponse[]>([])
const loading = ref(false)
const searchParams = ref<MatchRequestSearchParams>({})

// === 検索フィルタ（プラットフォーム全体検索のみ・teamId 指定時は非表示） ===
const prefectures = ref<PrefectureResponse[]>([])
const cities = ref<CityResponse[]>([])
const citiesLoading = ref(false)
const selectedPrefecture = ref<string | null>(null)
const selectedCity = ref<string | null>(null)
const selectedCategory = ref<MatchCategory | null>(null)
const keyword = ref<string>('')
const historyItems = ref<MatchRequestSearchParams[]>([])

/** マッチング募集のカテゴリー選択肢（teams/[slug]/matching.vue の作成ダイアログと同一区分をi18nキー化） */
const categoryOptions = computed(() => [
  { label: t('matching.category.ANY'), value: 'ANY' as MatchCategory },
  { label: t('matching.category.ELEMENTARY'), value: 'ELEMENTARY' as MatchCategory },
  { label: t('matching.category.JUNIOR_HIGH'), value: 'JUNIOR_HIGH' as MatchCategory },
  { label: t('matching.category.HIGH_SCHOOL'), value: 'HIGH_SCHOOL' as MatchCategory },
  { label: t('matching.category.UNIVERSITY'), value: 'UNIVERSITY' as MatchCategory },
  { label: t('matching.category.ADULT'), value: 'ADULT' as MatchCategory },
  { label: t('matching.category.SENIOR'), value: 'SENIOR' as MatchCategory },
])

function currentUserId(): number | null {
  return authStore.user?.id ?? null
}

async function loadCities(prefCode: string) {
  citiesLoading.value = true
  cities.value = []
  try {
    const res = await getCities(prefCode)
    cities.value = res.data
  } catch { /* 市区町村取得失敗は非致命的（フィルタが空になるだけ） */ }
  finally { citiesLoading.value = false }
}

async function onPrefectureChange() {
  selectedCity.value = null
  cities.value = []
  if (selectedPrefecture.value) await loadCities(selectedPrefecture.value)
}

function buildParams(): MatchRequestSearchParams {
  return {
    prefecture_code: selectedPrefecture.value ?? undefined,
    city_code: selectedCity.value ?? undefined,
    category: selectedCategory.value ?? undefined,
    keyword: keyword.value.trim() || undefined,
  }
}

/** 検索条件をフィルタUIへ反映し、検索を実行する（履歴・自動記憶への反映有無を指定可能） */
async function applyParams(params: MatchRequestSearchParams, persistHistory: boolean): Promise<void> {
  selectedPrefecture.value = params.prefecture_code ?? null
  selectedCategory.value = (params.category as MatchCategory | undefined) ?? null
  keyword.value = params.keyword ?? ''
  if (params.prefecture_code) {
    await loadCities(params.prefecture_code)
    selectedCity.value = params.city_code ?? null
  } else {
    selectedCity.value = null
    cities.value = []
  }
  searchParams.value = params

  const userId = currentUserId()
  if (userId) {
    saveLast(userId, params)
    if (persistHistory) pushHistory(userId, params)
    historyItems.value = loadHistory(userId)
  }
  await load()
}

async function handleSearch() {
  await applyParams(buildParams(), true)
}

async function handleClear() {
  await applyParams({}, false)
}

function applyHistoryItem(params: MatchRequestSearchParams) {
  void applyParams(params, false)
}

/** 検索履歴チップの要約ラベル（都道府県名 / カテゴリ名 / キーワード） */
function historyLabel(params: MatchRequestSearchParams): string {
  const parts: string[] = []
  if (params.prefecture_code) {
    const pref = prefectures.value.find(p => p.code === params.prefecture_code)
    parts.push(pref?.name ?? params.prefecture_code)
  }
  if (params.city_code) {
    const city = cities.value.find(c => c.code === params.city_code)
    if (city) parts.push(city.name)
  }
  if (params.category) {
    parts.push(t(`matching.category.${params.category}`))
  }
  if (params.keyword) parts.push(params.keyword)
  return parts.join(' / ')
}

async function load() {
  loading.value = true
  try {
    if (props.teamId) {
      const res = await getTeamRequests(props.teamId)
      requests.value = res.data
    } else {
      const res = await searchRequests(searchParams.value)
      requests.value = res.data
    }
  } catch { showError('募集一覧の取得に失敗しました') }
  finally { loading.value = false }
}

function getStatusClass(s: string): string {
  switch (s) { case 'OPEN': return 'bg-green-100 text-green-700'; case 'MATCHED': return 'bg-blue-100 text-blue-700'; case 'CANCELLED': return 'bg-red-100 text-red-600'; default: return 'bg-surface-100 text-surface-500' }
}

onMounted(async () => {
  if (!props.teamId) {
    try {
      const res = await getPrefectures()
      prefectures.value = res.data
    } catch { /* 都道府県取得失敗は非致命的（フィルタが空になるだけ） */ }

    const userId = currentUserId()
    if (userId) {
      historyItems.value = loadHistory(userId)
      const last = loadLast(userId)
      if (last && Object.keys(last).length > 0) {
        await applyParams(last, false)
        return
      }
    }
  }
  await load()
})
defineExpose({ refresh: load })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">{{ t('matching.listHeading') }}</h2>
      <Button v-if="teamId" :label="t('matching.create.button')" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <!-- 検索フィルタ（プラットフォーム全体検索のみ） -->
    <SectionCard v-if="!teamId" class="mb-4">
      <div class="flex flex-wrap items-end gap-3" data-testid="matching-filter-bar">
        <div class="flex flex-col gap-1">
          <label class="text-xs font-medium text-surface-500">{{ t('matching.filter.prefecture') }}</label>
          <Select
            v-model="selectedPrefecture"
            :options="prefectures"
            option-label="name"
            option-value="code"
            :placeholder="t('matching.filter.allPrefectures')"
            show-clear
            class="w-44 field-bordered"
            :aria-label="t('matching.filter.prefecture')"
            data-testid="matching-prefecture-select"
            @change="onPrefectureChange"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-xs font-medium text-surface-500">{{ t('matching.filter.city') }}</label>
          <Select
            v-model="selectedCity"
            :options="cities"
            option-label="name"
            option-value="code"
            :placeholder="t('matching.filter.allCities')"
            show-clear
            :disabled="!selectedPrefecture || citiesLoading"
            :loading="citiesLoading"
            class="w-48 field-bordered"
            :aria-label="t('matching.filter.city')"
            data-testid="matching-city-select"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-xs font-medium text-surface-500">{{ t('matching.filter.category') }}</label>
          <Select
            v-model="selectedCategory"
            :options="categoryOptions"
            option-label="label"
            option-value="value"
            :placeholder="t('matching.filter.allCategories')"
            show-clear
            class="w-44 field-bordered"
            :aria-label="t('matching.filter.category')"
            data-testid="matching-category-select"
          />
        </div>
        <div class="flex min-w-[200px] flex-1 flex-col gap-1">
          <label class="text-xs font-medium text-surface-500">{{ t('matching.filter.keyword') }}</label>
          <IconField>
            <InputIcon class="pi pi-search" />
            <InputText
              v-model="keyword"
              :placeholder="t('matching.filter.keyword')"
              class="w-full field-bordered"
              :aria-label="t('matching.filter.keyword')"
              data-testid="matching-keyword-input"
              @keyup.enter="handleSearch"
            />
          </IconField>
        </div>
        <div class="flex gap-2">
          <Button
            :label="t('matching.filter.search')"
            icon="pi pi-search"
            data-testid="matching-search-button"
            @click="handleSearch"
          />
          <Button
            :label="t('matching.filter.clear')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            data-testid="matching-clear-button"
            @click="handleClear"
          />
        </div>
      </div>

      <!-- 検索履歴チップ -->
      <div v-if="historyItems.length > 0" class="mt-4 flex flex-wrap items-center gap-2">
        <span class="text-xs font-medium text-surface-500">{{ t('matching.history.title') }}</span>
        <Chip
          v-for="(item, idx) in historyItems"
          :key="idx"
          :label="historyLabel(item)"
          class="cursor-pointer"
          :data-testid="`matching-history-chip-${idx}`"
          @click="applyHistoryItem(item)"
        />
      </div>
    </SectionCard>

    <div v-if="loading" class="flex justify-center py-8"><LoadingBounce /></div>
    <DashboardEmptyState v-else-if="requests.length === 0" icon="pi pi-search" :message="t('matching.empty')" />
    <div v-else class="flex flex-col gap-3">
      <button v-for="req in requests" :key="req.id" class="rounded-xl border border-surface-300 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm" @click="emit('select', req)">
        <div class="mb-2 flex items-center gap-2">
          <span :class="getStatusClass(req.status?.status ?? '')" class="rounded px-2 py-0.5 text-xs font-medium">{{ req.status?.status }}</span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">{{ req.content?.activityType }}</span>
          <span v-if="req.participants?.level !== 'ANY'" class="text-xs text-surface-400">{{ req.participants?.level }}</span>
        </div>
        <h3 class="mb-1 text-sm font-semibold">{{ req.content?.title }}</h3>
        <div class="flex items-center gap-3 text-xs text-surface-400">
          <span>{{ req.team?.name }}</span>
          <span v-if="req.team?.averageRating"><i class="pi pi-star-fill text-amber-400" /> {{ req.team.averageRating?.toFixed(1) }}</span>
          <span>{{ relativeTime(req.createdAt) }}</span>
          <span><i class="pi pi-users" /> {{ req.status?.proposalCount }}件の応募</span>
        </div>
      </button>
    </div>
  </div>
</template>
