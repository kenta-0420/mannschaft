<script setup lang="ts">
/**
 * F12.6 Q&A・ヘルプページ。
 *
 * 全カテゴリの Q&A を収集し、カテゴリタブ + インクリメンタル検索で絞り込む。
 * PWA/オフライン設問では PWA インストール CTA を併置することで
 * 「調べる」から「解決する」への導線を一枚で完結させる。
 */
import type { QaItem } from '~/composables/useQaSearch'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t, tm } = useI18n()
const route = useRoute()

type QaCategory = 'basic' | 'pwa' | 'offline' | 'troubleshooting'
type QaCategoryFilter = 'all' | QaCategory

const CATEGORIES: readonly QaCategory[] = ['basic', 'pwa', 'offline', 'troubleshooting'] as const
const CATEGORY_FILTERS: readonly QaCategoryFilter[] = ['all', ...CATEGORIES] as const

const searchQuery = ref('')
const selectedCategory = ref<QaCategoryFilter>('all')

// i18n から全カテゴリの Q&A を吸い上げて QaItem[] に正規化する
// tm() は locale message を返すが値が特殊ラッパーの可能性があるため t() で個別解決する
const allItems = computed<QaItem[]>(() => {
  return CATEGORIES.flatMap((category) => {
    const items = tm(`qa.items.${category}`) as Record<string, unknown> | null
    if (!items || typeof items !== 'object') return []
    return Object.keys(items).map((key) => ({
      id: `${category}-${key}`,
      category,
      question: t(`qa.items.${category}.${key}.question`),
      answer: t(`qa.items.${category}.${key}.answer`),
    }))
  })
})

// カテゴリで絞り込み
const categoryFiltered = computed<QaItem[]>(() => {
  if (selectedCategory.value === 'all') return allItems.value
  return allItems.value.filter((item) => item.category === selectedCategory.value)
})

const { filteredItems: displayedItems, highlightedText, hasResults, resultCount } = useQaSearch(
  categoryFiltered,
  searchQuery,
)

const isSearching = computed(() => searchQuery.value.trim().length > 0)

// PWA CTA は基本カテゴリ "all" または "pwa" 選択時のみ表示する
const showPwaCta = computed(() => selectedCategory.value === 'all' || selectedCategory.value === 'pwa')

function selectCategory(category: QaCategoryFilter) {
  selectedCategory.value = category
}

function isCategoryOf(value: string): value is QaCategory {
  return (CATEGORIES as readonly string[]).includes(value)
}

// ?category=pwa で初期カテゴリを指定できるようにする
onMounted(() => {
  const raw = route.query.category
  const cat = Array.isArray(raw) ? raw[0] : raw
  if (typeof cat === 'string' && isCategoryOf(cat)) {
    selectedCategory.value = cat
  }
})

useSeoMeta({
  title: () => `${t('qa.title')} | Mannschaft`,
  description: () => t('qa.description'),
  ogTitle: () => t('qa.title'),
  ogDescription: () => t('qa.description'),
  ogType: 'website',
})

// SEO 用 JSON-LD FAQPage。検索エンジンが Q&A 構造を解釈できるよう全項目を埋め込む
useHead({
  script: [
    {
      type: 'application/ld+json',
      innerHTML: computed(() =>
        JSON.stringify({
          '@context': 'https://schema.org',
          '@type': 'FAQPage',
          mainEntity: allItems.value.map((item) => ({
            '@type': 'Question',
            name: item.question,
            acceptedAnswer: {
              '@type': 'Answer',
              text: item.answer,
            },
          })),
        }),
      ),
    },
  ],
})
</script>

<template>
  <main class="mx-auto w-full max-w-[900px] px-4 py-8 md:px-6">
    <!-- ヘッダー -->
    <header class="mb-8">
      <h1 class="text-3xl font-bold text-surface-900 dark:text-white">
        {{ t('qa.title') }}
      </h1>
      <p class="mt-2 text-sm text-surface-600 dark:text-surface-300">
        {{ t('qa.description') }}
      </p>
    </header>

    <!-- チュートリアル誘導バナー -->
    <section
      class="mb-8 overflow-hidden rounded-2xl bg-gradient-to-br from-emerald-500 to-emerald-700 p-6 text-white shadow-md"
      :aria-label="t('qa.tutorial_banner.title')"
    >
      <div class="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div class="flex items-start gap-4">
          <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-white/20">
            <i class="pi pi-book text-2xl" />
          </div>
          <div>
            <h2 class="text-lg font-bold">
              {{ t('qa.tutorial_banner.title') }}
            </h2>
            <p class="mt-1 text-sm text-white/90">
              {{ t('qa.tutorial_banner.description') }}
            </p>
          </div>
        </div>
        <NuxtLink to="/my/onboarding" class="shrink-0">
          <Button
            :label="t('qa.tutorial_banner.button')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            severity="contrast"
          />
        </NuxtLink>
      </div>
    </section>

    <!-- 検索フィールド -->
    <section class="mb-6 flex justify-center">
      <div class="relative w-full max-w-[600px]">
        <label for="qa-search" class="sr-only">{{ t('qa.search_placeholder') }}</label>
        <i
          class="pi pi-search pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-surface-400"
          aria-hidden="true"
        />
        <InputText
          id="qa-search"
          v-model="searchQuery"
          :placeholder="t('qa.search_placeholder')"
          class="w-full pl-10"
          :aria-label="t('qa.search_placeholder')"
        />
      </div>
    </section>

    <!-- カテゴリタブ -->
    <section
      class="mb-6 flex gap-2 overflow-x-auto pb-2 flex-nowrap"
      role="tablist"
      :aria-label="t('qa.category.all')"
    >
      <Button
        v-for="category in CATEGORY_FILTERS"
        :key="category"
        :label="t(`qa.category.${category}`)"
        :severity="selectedCategory === category ? 'primary' : 'secondary'"
        :outlined="selectedCategory !== category"
        size="small"
        role="tab"
        :aria-selected="selectedCategory === category"
        class="shrink-0"
        @click="selectCategory(category)"
      />
    </section>

    <!-- 結果件数表示（検索時のみ） -->
    <p
      v-if="isSearching"
      class="mb-4 text-sm text-surface-500 dark:text-surface-400"
      aria-live="polite"
    >
      {{ t('qa.result_count', { count: resultCount }) }}
    </p>

    <!-- PWA インストール CTA（アコーディオン上部に目立たせる） -->
    <section v-if="showPwaCta" class="mb-8">
      <h2 class="mb-2 text-lg font-semibold text-surface-800 dark:text-surface-100">
        {{ t('qa.pwa_cta.title') }}
      </h2>
      <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
        {{ t('qa.pwa_cta.description') }}
      </p>
      <PwaInstallButton />
    </section>

    <!-- Q&A アコーディオン / 結果ゼロ -->
    <section aria-labelledby="qa-list-heading">
      <h2 id="qa-list-heading" class="sr-only">{{ t('qa.title') }}</h2>

      <QaAccordion
        v-if="hasResults"
        :items="displayedItems"
        :search-query="searchQuery"
        :highlight-fn="highlightedText"
        :open-default="true"
      />

      <div
        v-else
        class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-surface-300 bg-surface-50 py-12 text-center text-surface-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-400"
      >
        <i class="pi pi-inbox text-4xl" aria-hidden="true" />
        <p class="text-sm">{{ t('qa.no_results') }}</p>
      </div>
    </section>
  </main>
</template>
