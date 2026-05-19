<script setup lang="ts">
import type { FetchError } from 'ofetch'
import type { PublicPostSummary, PublicTeamResponse, SpringPage } from '~/types/public'

/**
 * F19.1 公開チーム詳細ページ。
 *
 * - 未ログインアクセス可（layout: public / auth.global なし）
 * - SSR 有効（OGP / SEO 用に基本情報を SSR レスポンスに反映）
 * - PRIVATE / archived / 不在は 404
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §8.1 / §4.1 / §4.2
 */
definePageMeta({
  layout: 'public',
})

const route = useRoute()
const { t } = useI18n()
const { fetchPublicTeam, fetchPublicTeamPosts } = usePublicApi()

const rawId = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
const teamId = Number(rawId)

// パスパラメータが数値でない場合は 404
if (!Number.isFinite(teamId) || teamId <= 0) {
  throw createError({
    statusCode: 404,
    statusMessage: t('public.error.notFound'),
    fatal: true,
  })
}

// チーム本体（SSR 必須）
const { data: team, error: teamError } = await useAsyncData<PublicTeamResponse>(
  `public-team-${teamId}`,
  () => fetchPublicTeam(teamId),
)

if (teamError.value || !team.value) {
  const status = (teamError.value as FetchError | null)?.response?.status ?? 404
  throw createError({
    statusCode: status === 429 ? 429 : 404,
    statusMessage: status === 429 ? t('public.error.rateLimit') : t('public.error.notFound'),
    fatal: true,
  })
}

// 投稿一覧（ページング）
const currentPage = ref(0)
const pageSize = 20

const { data: postsPage, refresh: refreshPosts } = await useAsyncData<SpringPage<PublicPostSummary>>(
  `public-team-${teamId}-posts`,
  () => fetchPublicTeamPosts(teamId, currentPage.value, pageSize),
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

// OGP / SEO メタタグ（§9.1 Phase 1 最小タグ）
const config = useRuntimeConfig()
const canonicalUrl = computed(
  () => `${config.public.apiBase}`.replace(/\/api\/v1$/, '') + `/public/teams/${teamId}`,
)

useSeoMeta({
  title: () => t('public.team.title', { name: team.value?.name ?? '' }),
  description: () => team.value?.philosophy ?? t('public.meta.ogDescriptionDefault'),
  ogTitle: () => t('public.team.title', { name: team.value?.name ?? '' }),
  ogDescription: () => team.value?.philosophy ?? t('public.meta.ogDescriptionDefault'),
  ogImage: () => team.value?.bannerUrl ?? team.value?.iconUrl ?? '',
  ogType: 'website',
  ogUrl: () => canonicalUrl.value,
  twitterCard: 'summary_large_image',
  twitterTitle: () => t('public.team.title', { name: team.value?.name ?? '' }),
  twitterDescription: () => team.value?.philosophy ?? t('public.meta.ogDescriptionDefault'),
  twitterImage: () => team.value?.bannerUrl ?? team.value?.iconUrl ?? '',
})

function detailHref(postId: number): string {
  return `/public/teams/${teamId}/posts/${postId}`
}
</script>

<template>
  <div v-if="team" class="space-y-10">
    <PublicTeamHeader :team="team" />

    <section aria-labelledby="public-posts-heading" class="space-y-4">
      <div class="flex items-center justify-between">
        <h2 id="public-posts-heading" class="text-xl font-bold">
          {{ t('public.posts.sectionTitle') }}
        </h2>
        <span v-if="totalElements > 0" class="text-sm text-surface-500">
          {{ t('public.posts.totalCount', { n: totalElements }) }}
        </span>
      </div>

      <p v-if="posts.length === 0" class="rounded-lg bg-surface-50 p-6 text-center text-sm text-surface-500 dark:bg-surface-800">
        {{ t('public.posts.empty') }}
      </p>

      <div v-else class="grid gap-4 sm:grid-cols-2">
        <PublicPostCard
          v-for="post in posts"
          :key="`${post.sourceType}-${post.sourceId}`"
          :post="post"
          :detail-href="detailHref(post.sourceId)"
        />
      </div>

      <nav v-if="totalPages > 1" class="flex items-center justify-between pt-2" aria-label="pagination">
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

    <LoginCtaCard scope-kind="TEAM" :scope-id="teamId" />
  </div>
</template>
