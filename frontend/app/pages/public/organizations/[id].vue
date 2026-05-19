<script setup lang="ts">
import type { FetchError } from 'ofetch'
import type {
  PublicOrganizationResponse,
  PublicPostSummary,
  SpringPage,
} from '~/types/public'

/**
 * F19.1 公開組織詳細ページ。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §8.1 / §4.1
 */
definePageMeta({
  layout: 'public',
})

const route = useRoute()
const { t } = useI18n()
const { fetchPublicOrganization, fetchPublicOrganizationPosts } = usePublicApi()

const rawId = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
const orgId = Number(rawId)

if (!Number.isFinite(orgId) || orgId <= 0) {
  throw createError({
    statusCode: 404,
    statusMessage: t('public.error.notFound'),
    fatal: true,
  })
}

const { data: organization, error: orgError } = await useAsyncData<PublicOrganizationResponse>(
  `public-org-${orgId}`,
  () => fetchPublicOrganization(orgId),
)

if (orgError.value || !organization.value) {
  const status = (orgError.value as FetchError | null)?.response?.status ?? 404
  throw createError({
    statusCode: status === 429 ? 429 : 404,
    statusMessage: status === 429 ? t('public.error.rateLimit') : t('public.error.notFound'),
    fatal: true,
  })
}

const currentPage = ref(0)
const pageSize = 20

const { data: postsPage, refresh: refreshPosts } = await useAsyncData<SpringPage<PublicPostSummary>>(
  `public-org-${orgId}-posts`,
  () => fetchPublicOrganizationPosts(orgId, currentPage.value, pageSize),
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

const config = useRuntimeConfig()
const canonicalUrl = computed(
  () => `${config.public.apiBase}`.replace(/\/api\/v1$/, '') + `/public/organizations/${orgId}`,
)

useSeoMeta({
  title: () => t('public.organization.title', { name: organization.value?.name ?? '' }),
  description: () => organization.value?.philosophy ?? t('public.meta.ogDescriptionDefault'),
  ogTitle: () =>
    t('public.organization.title', { name: organization.value?.name ?? '' }),
  ogDescription: () => organization.value?.philosophy ?? t('public.meta.ogDescriptionDefault'),
  ogImage: () => organization.value?.bannerUrl ?? organization.value?.iconUrl ?? '',
  ogType: 'website',
  ogUrl: () => canonicalUrl.value,
  twitterCard: 'summary_large_image',
  twitterTitle: () =>
    t('public.organization.title', { name: organization.value?.name ?? '' }),
  twitterDescription: () =>
    organization.value?.philosophy ?? t('public.meta.ogDescriptionDefault'),
  twitterImage: () => organization.value?.bannerUrl ?? organization.value?.iconUrl ?? '',
})

function detailHref(postId: number): string {
  return `/public/organizations/${orgId}/posts/${postId}`
}
</script>

<template>
  <div v-if="organization" class="space-y-10">
    <PublicOrganizationHeader :organization="organization" />

    <section aria-labelledby="public-org-posts-heading" class="space-y-4">
      <div class="flex items-center justify-between">
        <h2 id="public-org-posts-heading" class="text-xl font-bold">
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

    <LoginCtaCard scope-kind="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
