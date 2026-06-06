<script setup lang="ts">
import dayjs from 'dayjs'
import type { FetchError } from 'ofetch'
import type { PublicUserPostSummary, PublicUserProfile, SpringPage } from '~/types/public'

/**
 * F19.1 Phase 6-A: 公開ユーザープロフィールページ。
 *
 * - 未ログインアクセス可（layout: public / auth.global なし）
 * - SSR 有効（OGP / SEO 用に基本情報を SSR レスポンスに反映）
 * - public_profile_enabled=false / 不在 / 削除済みは 404（IDOR 対策）
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6
 */
definePageMeta({
  layout: 'public',
})

const route = useRoute()
const { t } = useI18n()
const { fetchPublicUserProfile, fetchPublicUserPosts } = usePublicApi()
const { userTimezone } = useDatetime()

const rawId = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
const userId = Number(rawId)

// パスパラメータが数値でない場合は 404
if (!Number.isFinite(userId) || userId <= 0) {
  throw createError({
    statusCode: 404,
    statusMessage: t('public.error.notFound'),
    fatal: true,
  })
}

// プロフィール本体（SSR 必須）
const { data: userProfile, error: profileError } = await useAsyncData<PublicUserProfile>(
  `public-user-${userId}`,
  () => fetchPublicUserProfile(userId),
)

if (profileError.value || !userProfile.value) {
  const status = (profileError.value as FetchError | null)?.response?.status ?? 404
  throw createError({
    statusCode: status === 429 ? 429 : 404,
    statusMessage: status === 429 ? t('public.error.rateLimit') : t('public.error.notFound'),
    fatal: true,
  })
}

// 投稿一覧（ページング）
const currentPage = ref(0)
const pageSize = 20

const { data: postsPage, refresh: refreshPosts } = await useAsyncData<SpringPage<PublicUserPostSummary>>(
  `public-user-${userId}-posts`,
  () => fetchPublicUserPosts(userId, currentPage.value, pageSize),
  { watch: [currentPage] },
)

const posts = computed(() => postsPage.value?.content ?? [])
const totalPages = computed(() => postsPage.value?.totalPages ?? 1)
const totalElements = computed(() => postsPage.value?.totalElements ?? 0)

async function goPage(next: number) {
  if (next < 0 || next >= totalPages.value) return
  currentPage.value = next
  await refreshPosts()
}

// OGP / SEO メタタグ
const config = useRuntimeConfig()
const canonicalUrl = computed(
  () => `${config.public.apiBase}`.replace(/\/api\/v1$/, '') + `/public/users/${userId}`,
)

useSeoMeta({
  title: () => t('public.userProfile.title', { name: userProfile.value?.displayName ?? '' }),
  description: () => t('public.meta.ogDescriptionDefault'),
  ogTitle: () => t('public.userProfile.title', { name: userProfile.value?.displayName ?? '' }),
  ogDescription: () => t('public.meta.ogDescriptionDefault'),
  ogImage: () => userProfile.value?.avatarUrl ?? '',
  ogType: 'profile',
  ogUrl: () => canonicalUrl.value,
  twitterCard: 'summary',
  twitterTitle: () => t('public.userProfile.title', { name: userProfile.value?.displayName ?? '' }),
  twitterDescription: () => t('public.meta.ogDescriptionDefault'),
  twitterImage: () => userProfile.value?.avatarUrl ?? '',
})

// F19.1 Phase 3: hreflang 6言語 + canonical + JSON-LD Person スキーマ
useSeoPublicPage({
  canonicalPath: `/public/users/${userId}`,
  title: () => t('public.userProfile.title', { name: userProfile.value?.displayName ?? '' }),
  description: () => t('public.meta.ogDescriptionDefault'),
  imageUrl: () => userProfile.value?.avatarUrl ?? undefined,
  jsonLd: () => userProfile.value ? {
    '@context': 'https://schema.org',
    '@type': 'Person',
    name: userProfile.value.displayName,
    url: canonicalUrl.value,
    image: userProfile.value.avatarUrl ?? undefined,
  } : undefined,
})

/** 投稿詳細リンクを生成する。scopeType に応じて公開チーム/組織の投稿詳細ページへ誘導する。 */
function postDetailHref(post: PublicUserPostSummary): string {
  const base = post.scopeType === 'TEAM'
    ? `/public/teams/${post.scopeId}/posts/${post.postId}`
    : `/public/organizations/${post.scopeId}/posts/${post.postId}`
  return base
}

/** "YYYY-MM-DD" 形式の日付を年月表示に変換する（例: "2024年3月"）。 */
function formatMemberSince(dateStr: string): string {
  const d = dayjs.tz(dateStr, userTimezone.value)
  if (!d.isValid()) return dateStr
  return t('public.userProfile.memberSince', {
    date: `${d.year()}年${d.month() + 1}月`,
  })
}
</script>

<template>
  <div v-if="userProfile" data-testid="public-user-profile" class="space-y-10">
    <!-- プロフィールヘッダー -->
    <section class="flex flex-col items-center gap-4 sm:flex-row sm:items-start sm:gap-6">
      <div class="shrink-0">
        <img
          v-if="userProfile.avatarUrl"
          :src="userProfile.avatarUrl"
          :alt="userProfile.displayName"
          class="h-24 w-24 rounded-full object-cover ring-2 ring-surface-200 dark:ring-surface-600"
        >
        <div
          v-else
          class="flex h-24 w-24 items-center justify-center rounded-full bg-primary/10 text-3xl text-primary"
        >
          <i class="pi pi-user" aria-hidden="true" />
        </div>
      </div>
      <div class="space-y-1 text-center sm:text-left">
        <h1 class="text-2xl font-bold">{{ userProfile.displayName }}</h1>
        <p class="text-sm text-surface-500">{{ formatMemberSince(userProfile.memberSince) }}</p>
      </div>
    </section>

    <!-- 公開投稿一覧 -->
    <section aria-labelledby="user-posts-heading" class="space-y-4">
      <div class="flex items-center justify-between">
        <h2 id="user-posts-heading" class="text-xl font-bold">
          {{ t('public.userProfile.posts') }}
        </h2>
        <span v-if="totalElements > 0" class="text-sm text-surface-500">
          {{ t('public.posts.totalCount', { n: totalElements }) }}
        </span>
      </div>

      <p
        v-if="posts.length === 0"
        class="rounded-lg bg-surface-50 p-6 text-center text-sm text-surface-500 dark:bg-surface-800"
      >
        {{ t('public.userProfile.noPosts') }}
      </p>

      <ul v-else data-testid="user-post-list" class="space-y-3">
        <li
          v-for="post in posts"
          :key="post.postId"
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
        >
          <NuxtLink :to="postDetailHref(post)" class="block space-y-1">
            <p class="font-medium text-primary">{{ post.title }}</p>
            <p class="text-xs text-surface-500">
              {{ post.scopeName }}
              &middot;
              {{ dayjs.tz(post.createdAt, userTimezone).format('YYYY/MM/DD') }}
            </p>
          </NuxtLink>
        </li>
      </ul>

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
    </section>

    <LoginCtaCard scope-kind="TEAM" :scope-id="String(userId)" />
  </div>
</template>
