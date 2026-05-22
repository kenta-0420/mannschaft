<script setup lang="ts">
import type { PublicOrganizationSearchResult, SpringPage } from '~/types/public'
import { PREFECTURES } from '~/constants/prefectures'

/**
 * F19.1 Phase 4 公開組織検索ページ。
 *
 * - 未ログインアクセス可（layout: public / auth.global なし）
 * - キーワード・都道府県フィルタ付きのページング検索
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1
 */
definePageMeta({
  layout: 'public',
})

const { t } = useI18n()
const { searchPublicOrganizations } = usePublicApi()

// フィルタ状態
const keyword = ref('')
const prefecture = ref('')
const currentPage = ref(0)
const pageSize = 20

// 検索結果
const { data: resultsPage, pending, refresh } = await useAsyncData<SpringPage<PublicOrganizationSearchResult>>(
  'discover-organizations',
  () =>
    searchPublicOrganizations({
      keyword: keyword.value || undefined,
      prefecture: prefecture.value || undefined,
      page: currentPage.value,
      size: pageSize,
    }),
  { lazy: true },
)

const organizations = computed(() => resultsPage.value?.content ?? [])
const totalPages = computed(() => resultsPage.value?.totalPages ?? 0)
const totalElements = computed(() => resultsPage.value?.totalElements ?? 0)

async function handleSearch() {
  currentPage.value = 0
  await refresh()
}

async function goPage(next: number) {
  if (next < 0 || next >= totalPages.value) return
  currentPage.value = next
  await refresh()
}

// SEO
useSeoMeta({
  title: () => t('public.discover.organizations.title'),
  description: () => t('public.meta.ogDescriptionDefault'),
})

useSeoPublicPage({
  canonicalPath: '/discover/organizations',
  title: () => t('public.discover.organizations.title'),
  description: () => t('public.meta.ogDescriptionDefault'),
})
</script>

<template>
  <div class="space-y-8">
    <!-- ページタイトル -->
    <header>
      <h1 class="text-2xl font-bold text-surface-900 dark:text-surface-50 sm:text-3xl">
        {{ t('public.discover.organizations.title') }}
      </h1>
    </header>

    <!-- フィルタバー -->
    <section aria-labelledby="filter-heading" class="rounded-lg bg-surface-50 p-4 dark:bg-surface-800">
      <h2 id="filter-heading" class="sr-only">
        {{ t('public.discover.filter.keyword') }}
      </h2>
      <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
        <!-- キーワード -->
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ t('public.discover.filter.keyword') }}
          </label>
          <InputText
            v-model="keyword"
            :placeholder="t('public.discover.organizations.searchPlaceholder')"
            class="w-full"
            @keyup.enter="handleSearch"
          />
        </div>

        <!-- 都道府県 -->
        <div class="w-full sm:w-48">
          <label class="mb-1 block text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ t('public.discover.filter.prefecture') }}
          </label>
          <Select
            v-model="prefecture"
            :options="['', ...PREFECTURES]"
            class="w-full"
          >
            <template #value="{ value }">
              {{ value || t('public.discover.filter.all') }}
            </template>
            <template #option="{ option }">
              {{ option || t('public.discover.filter.all') }}
            </template>
          </Select>
        </div>

        <!-- 検索ボタン -->
        <Button
          :label="t('public.discover.filter.search')"
          :loading="pending"
          @click="handleSearch"
        />
      </div>
    </section>

    <!-- 件数表示 -->
    <div v-if="resultsPage" class="text-sm text-surface-500">
      {{ t('public.posts.totalCount', { n: totalElements }) }}
    </div>

    <!-- 読み込み中 -->
    <div v-if="pending" class="py-12 text-center text-sm text-surface-500">
      {{ t('public.posts.loading') }}
    </div>

    <!-- 結果なし -->
    <p
      v-else-if="!pending && organizations.length === 0 && resultsPage"
      class="rounded-lg bg-surface-50 p-8 text-center text-sm text-surface-500 dark:bg-surface-800"
    >
      {{ t('public.discover.organizations.noResults') }}
    </p>

    <!-- 検索結果一覧 -->
    <div v-else-if="organizations.length > 0" class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <DiscoverOrganizationCard
        v-for="org in organizations"
        :key="org.id"
        :organization="org"
      />
    </div>

    <!-- ページネーション -->
    <nav
      v-if="totalPages > 1"
      class="flex items-center justify-between pt-2"
      aria-label="pagination"
    >
      <Button
        :disabled="currentPage <= 0"
        severity="secondary"
        outlined
        size="small"
        :label="t('public.posts.prev')"
        @click="goPage(currentPage - 1)"
      />
      <span class="text-sm text-surface-500">
        {{ t('public.posts.page', { page: currentPage + 1, total: totalPages }) }}
      </span>
      <Button
        :disabled="currentPage >= totalPages - 1"
        severity="secondary"
        outlined
        size="small"
        :label="t('public.posts.next')"
        @click="goPage(currentPage + 1)"
      />
    </nav>
  </div>
</template>
