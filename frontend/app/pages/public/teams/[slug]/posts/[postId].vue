<script setup lang="ts">
import type { FetchError } from 'ofetch'
import type { PublicPostDetail } from '~/types/public'

/**
 * F19.1 公開チーム投稿詳細ページ + OGP メタタグ。
 *
 * - SSR 必須（OGP クローラ向け）
 * - bodyHtml は BE サニタイズ済み + フロント二段サニタイズ
 * - 404: チーム PRIVATE / 投稿 public_visible=false / 不在
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.3 / §9.1
 */
definePageMeta({
  layout: 'public',
})

const route = useRoute()
const { t } = useI18n()
const { fetchPublicTeamPostDetail } = usePublicApi()

const rawTeamId = Array.isArray(route.params.slug) ? route.params.slug[0] : route.params.slug
const rawPostId = Array.isArray(route.params.postId) ? route.params.postId[0] : route.params.postId
const teamSlug = String(rawTeamId)
const postId = Number(rawPostId)

if (!teamSlug || !Number.isFinite(postId) || postId <= 0) {
  throw createError({
    statusCode: 404,
    statusMessage: t('public.error.notFound'),
    fatal: true,
  })
}

const { data: post, error: postError } = await useAsyncData<PublicPostDetail>(
  `public-team-${teamSlug}-post-${postId}`,
  () => fetchPublicTeamPostDetail(teamSlug, postId),
)

if (postError.value || !post.value) {
  const status = (postError.value as FetchError | null)?.response?.status ?? 404
  throw createError({
    statusCode: status === 429 ? 429 : 404,
    statusMessage: status === 429 ? t('public.error.rateLimit') : t('public.error.notFound'),
    fatal: true,
  })
}

// OGP / SEO メタタグ（§9.1 Phase 1 最小タグ）
const config = useRuntimeConfig()
const canonicalUrl = computed(
  () =>
    `${config.public.apiBase}`.replace(/\/api\/v1$/, '') +
    `/public/teams/${teamSlug}/posts/${postId}`,
)

const excerptForOgp = computed(() => {
  const body = post.value?.bodyHtml ?? ''
  return body.replace(/<[^>]+>/g, '').slice(0, 200)
})

useSeoMeta({
  title: () => post.value?.title ?? '',
  description: () => excerptForOgp.value,
  ogTitle: () => post.value?.title ?? '',
  ogDescription: () => excerptForOgp.value,
  ogImage: '',
  ogType: 'article',
  ogUrl: () => canonicalUrl.value,
  twitterCard: 'summary_large_image',
  twitterTitle: () => post.value?.title ?? '',
  twitterDescription: () => excerptForOgp.value,
})

// F19.1 Phase 3: hreflang 6言語 + canonical + JSON-LD Article スキーマ
useSeoPublicPage({
  canonicalPath: `/public/teams/${teamSlug}/posts/${postId}`,
  title: () => post.value?.title ?? '',
  description: () => excerptForOgp.value,
  jsonLd: () => post.value ? {
    '@context': 'https://schema.org',
    '@type': 'Article',
    headline: post.value.title,
    datePublished: post.value.publishedAt,
    author: {
      '@type': 'Person',
      name: post.value.author?.displayLabel ?? '投稿者',
    },
    publisher: {
      '@type': 'Organization',
      name: post.value.scope?.scopeName ?? '',
    },
  } : undefined,
})

const scopeHref = computed(() => `/public/teams/${teamSlug}`)
</script>

<template>
  <div v-if="post" class="space-y-8">
    <nav aria-label="breadcrumb">
      <NuxtLink :to="scopeHref" class="text-sm text-primary hover:underline">
        ← {{ post.scope.scopeName }}
      </NuxtLink>
    </nav>

    <PublicPostDetail :post="post" />

    <PublicPostCommentSection :post-id="postId" />

    <LoginCtaCard scope-kind="TEAM" :scope-id="teamSlug" />
  </div>
</template>
