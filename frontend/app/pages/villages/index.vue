<script setup lang="ts">
/**
 * F17.1 村機能 — 村一覧・検索ページ
 *
 * - 公開村（PUBLIC, 非 UNLISTED）を一覧表示
 * - 検索 (q) + 種別 (type) + カテゴリ (category) のフィルタ
 * - PrimeVue Paginator によるページネーション
 *
 * 関連:
 *   - 設計書: docs/features/F17.1_village_community.md §4.2
 *   - API:    GET /api/v1/villages/search
 *   - 型定義: ~/types/village
 *   - API ラッパ: ~/composables/useVillageApi
 */
import type { VillageResponse, VillageType } from '~/types/village'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const villageApi = useVillageApi()
const { handleApiError } = useErrorHandler()

// =====================================================================
// State
// =====================================================================

const villages = ref<VillageResponse[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const currentPage = ref(0)
const pageSize = 20

/** 種別フィルタ。null は「すべて」を意味し API へは送らない */
const filterType = ref<VillageType | null>(null)

const filterCategory = ref<string>('')
const searchQuery = ref<string>('')

// =====================================================================
// Options
// =====================================================================

const typeOptions = computed<{ label: string; value: VillageType }[]>(() => [
  { label: t('village.type.OFFICIAL'), value: 'OFFICIAL' },
  { label: t('village.type.COMMUNITY'), value: 'COMMUNITY' },
])

// =====================================================================
// Fetch
// =====================================================================

async function fetchVillages() {
  loading.value = true
  try {
    const result = await villageApi.searchVillages({
      q: searchQuery.value.trim() || undefined,
      type: filterType.value ?? undefined,
      category: filterCategory.value.trim() || undefined,
      page: currentPage.value,
      size: pageSize,
    })
    villages.value = result.content
    totalRecords.value = result.totalElements
  }
  catch (error) {
    handleApiError(error, t('village.search.title'))
  }
  finally {
    loading.value = false
  }
}

// =====================================================================
// 検索文字列の debounce (500ms)
// =====================================================================

let searchTimer: ReturnType<typeof setTimeout> | null = null

watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    currentPage.value = 0
    fetchVillages()
  }, 500)
})

// フィルタ変更で即時再検索（debounce 不要）
watch([filterType, filterCategory], () => {
  currentPage.value = 0
  fetchVillages()
})

// =====================================================================
// Paginator
// =====================================================================

function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  fetchVillages()
}

// =====================================================================
// Init
// =====================================================================

onMounted(() => {
  fetchVillages()
})

// =====================================================================
// Helpers
// =====================================================================

/** 使い方モーダル表示制御。 */
const showGuide = ref(false)

function avatarLabel(v: VillageResponse): string {
  // アイコン URL 未解決時の頭文字フォールバック
  return v.name.charAt(0) || '村'
}
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <!-- ヘッダー（PageHeader に作成ボタンと使い方を集約） -->
    <PageHeader :title="$t('village.search.title')" help @help="showGuide = true">
      <template #actions>
        <Button
          :label="$t('village.action.create')"
          icon="pi pi-plus"
          @click="navigateTo('/villages/create-request')"
        />
      </template>
    </PageHeader>

    <!-- 検索 + フィルタバー -->
    <div class="mb-6 flex flex-wrap items-center gap-3">
      <IconField class="flex-1 min-w-[240px]">
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="searchQuery"
          :placeholder="$t('village.search.placeholder')"
          class="w-full field-bordered"
        />
      </IconField>
      <Select
        v-model="filterType"
        :options="typeOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('village.search.filterType')"
        show-clear
        class="w-48 field-bordered"
      />
      <InputText
        v-model="filterCategory"
        :placeholder="$t('village.search.filterCategory')"
        class="w-48 field-bordered"
      />
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="villages.length === 0"
      icon="pi pi-search"
      :message="$t('village.search.empty')"
    />

    <!-- 村カード一覧 -->
    <template v-else>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <SectionCard
          v-for="village in villages"
          :key="village.id"
          class="cursor-pointer transition-shadow hover:shadow-md"
          role="button"
          tabindex="0"
          @click="navigateTo(`/villages/${village.id}`)"
          @keydown.enter="navigateTo(`/villages/${village.id}`)"
        >
          <!-- ヘッダー: アイコン + 名前 + 公式バッジ -->
          <div class="mb-3 flex items-center gap-3">
            <Avatar
              :label="avatarLabel(village)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <h3 class="truncate font-semibold">{{ village.name }}</h3>
                <Tag
                  v-if="village.isOfficial"
                  :value="$t('village.type.OFFICIAL')"
                  severity="success"
                  class="shrink-0 text-xs"
                />
              </div>
              <Tag
                v-if="village.category"
                :value="village.category"
                severity="info"
                class="mt-1 text-xs"
              />
            </div>
          </div>

          <!-- 説明文（最大 2 行省略） -->
          <p
            v-if="village.description"
            class="mb-3 line-clamp-2 text-sm text-surface-600 dark:text-surface-300"
          >
            {{ village.description }}
          </p>

          <!-- メタ情報 -->
          <div class="flex items-center justify-between text-sm text-surface-500 dark:text-surface-400">
            <span>
              <i class="pi pi-tag mr-1" />
              {{ $t(`village.type.${village.type}`) }}
            </span>
            <span>
              <i class="pi pi-users mr-1" />
              {{ $t('village.field.memberCount') }}: {{ village.memberCount }}
            </span>
          </div>
        </SectionCard>
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

    <!-- 使い方モーダル -->
    <VillageListGuideModal v-model:visible="showGuide" />
  </div>
</template>
