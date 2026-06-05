<script setup lang="ts">
import type { FetchError } from 'ofetch'
import type { PublicFaqItem } from '~/types/faq'
import type {
  PublicEventResponse,
  PublicOrganizationResponse,
  PublicPostSummary,
  PublicTimelinePostResponse,
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
const {
  fetchPublicOrganization,
  fetchPublicOrganizationPosts,
  fetchPublicOrgTimelinePosts,
  fetchPublicOrgEvents,
} = usePublicApi()

const rawId = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
const orgId = String(rawId)

if (!orgId) {
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

// F19.1 Phase 7: タイムライン投稿一覧（timelinePostsPublic = true の場合のみ表示）
const timelineCurrentPage = ref(0)
const { data: timelinePage, refresh: refreshTimeline } = await useAsyncData<SpringPage<PublicTimelinePostResponse>>(
  `public-org-${orgId}-timeline`,
  () => organization.value?.timelinePostsPublic
    ? fetchPublicOrgTimelinePosts(orgId, timelineCurrentPage.value, pageSize)
    : Promise.resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: pageSize, first: true, last: true, empty: true, numberOfElements: 0 }),
  { watch: [timelineCurrentPage] },
)

const timelinePosts = computed(() => timelinePage.value?.content ?? [])
const timelineTotalPages = computed(() => timelinePage.value?.totalPages ?? 1)
const timelineTotalElements = computed(() => timelinePage.value?.totalElements ?? 0)

async function goTimelinePage(next: number) {
  if (next < 0 || next >= timelineTotalPages.value) return
  timelineCurrentPage.value = next
  await refreshTimeline()
}

// F19.1 Phase 7: イベント一覧（publicEventsEnabled = true の場合のみ表示）
const eventCurrentPage = ref(0)
const { data: eventsPage, refresh: refreshEvents } = await useAsyncData<SpringPage<PublicEventResponse>>(
  `public-org-${orgId}-events`,
  () => organization.value?.publicEventsEnabled
    ? fetchPublicOrgEvents(orgId, eventCurrentPage.value, pageSize)
    : Promise.resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: pageSize, first: true, last: true, empty: true, numberOfElements: 0 }),
  { watch: [eventCurrentPage] },
)

const events = computed(() => eventsPage.value?.content ?? [])
const eventsTotalPages = computed(() => eventsPage.value?.totalPages ?? 1)
const eventsTotalElements = computed(() => eventsPage.value?.totalElements ?? 0)

async function goEventsPage(next: number) {
  if (next < 0 || next >= eventsTotalPages.value) return
  eventCurrentPage.value = next
  await refreshEvents()
}

// F21.1 §5.5 FAQ駆動GEO: 公開 FAQ 一覧（認証不要・回答済みのみ・固定→自由順で BE が返す）。
// 公開組織でも FAQ 0 件は正常状態（セクション非表示）なので 404/空は握りつぶさず空配列扱いとする。
const { fetchPublicOrgFaqs } = useFaqApi()
const { data: faqsData } = await useAsyncData<PublicFaqItem[]>(
  `public-org-${orgId}-faqs`,
  () => fetchPublicOrgFaqs(orgId),
  { default: () => [] },
)
const faqs = computed((): PublicFaqItem[] => faqsData.value ?? [])

/**
 * 公開 FAQ 1 件の表示用質問文を解決する。
 * 固定質問（questionKey 非 null）は i18n `faq.fixed.{key小文字}` で描画し、
 * 自由質問（questionKey null）は保存値 questionText をそのまま用いる。
 */
function faqQuestion(item: PublicFaqItem): string {
  return item.questionKey
    ? t(`faq.fixed.${item.questionKey.toLowerCase()}`)
    : (item.questionText ?? '')
}

// F21.1 GEO: 引用されやすい定義文。philosophy があればそれを、無ければ
// 地理情報入りの定義文を i18n で動的生成する。meta / og / twitter / JSON-LD で共有する。
const seoDescription = computed((): string => {
  const philosophy = organization.value?.philosophy?.trim()
  if (philosophy) return philosophy
  const name = organization.value?.name ?? ''
  const prefecture = organization.value?.prefecture ?? ''
  const city = organization.value?.city ?? ''
  // 地理情報が一切無い場合は名前のみの自然な定義文にフォールバックする。
  if (!prefecture && !city) {
    return t('public.organization.geoDescriptionNoLocation', { name })
  }
  return t('public.organization.geoDescription', { prefecture, city, name })
})

// F19.1 Phase 3 / F21.1: hreflang 6言語 + canonical + JSON-LD（@graph 化）。
// F21.1: canonical / baseUrl は useSeoPublicPage が単一ソースとして算出する。
// 先に呼び出して戻り値（canonicalUrl）を後続の useSeoMeta に流用する。
const { canonicalUrl } = useSeoPublicPage({
  canonicalPath: `/public/organizations/${orgId}`,
  title: () => t('public.organization.title', { name: organization.value?.name ?? '' }),
  description: () => seoDescription.value,
  imageUrl: () => organization.value?.bannerUrl ?? organization.value?.iconUrl ?? undefined,
  // F21.1 GEO: Organization（@id + address=PostalAddress + sameAs + description）と
  // BreadcrumbList（@id）を @graph 配列にまとめて注入する。canonical / baseUrl は
  // ctx 経由で受け取り単一ソース化する。undefined フィールドは
  // JSON.stringify が自動的に省くため出力はクリーンになる。
  jsonLd: (ctx) => {
    if (!organization.value) return undefined
    const graph: Record<string, unknown>[] = [
      {
        '@type': 'Organization',
        '@id': `${ctx?.canonicalUrl ?? ''}#organization`,
        name: organization.value.name,
        url: ctx?.canonicalUrl,
        logo: organization.value.iconUrl ?? undefined,
        description: seoDescription.value,
        address: (organization.value.prefecture || organization.value.city) ? {
          '@type': 'PostalAddress',
          addressCountry: 'JP',
          addressRegion: organization.value.prefecture ?? undefined,
          addressLocality: organization.value.city ?? undefined,
        } : undefined,
        sameAs: organization.value.homepageUrl ? [organization.value.homepageUrl] : undefined,
      },
      {
        '@type': 'BreadcrumbList',
        '@id': `${ctx?.canonicalUrl ?? ''}#breadcrumb`,
        itemListElement: [
          {
            '@type': 'ListItem',
            position: 1,
            name: t('public.breadcrumb.home'),
            item: ctx?.baseUrl,
          },
          {
            '@type': 'ListItem',
            position: 2,
            name: t('public.breadcrumb.discoverOrganizations'),
            item: `${ctx?.baseUrl ?? ''}/discover/organizations`,
          },
          {
            '@type': 'ListItem',
            position: 3,
            name: organization.value.name,
          },
        ],
      },
    ]
    // F21.1 §5.5 FAQ駆動GEO: 回答済み FAQ が 1 件以上のときのみ FAQPage ノードを追加する。
    // mainEntity は可視アコーディオン（faqQuestion / answer）と完全一致させる
    // （Google の「構造化データと可視内容の一致」要件）。
    if (faqs.value.length > 0) {
      graph.push({
        '@type': 'FAQPage',
        '@id': `${ctx?.canonicalUrl ?? ''}#faq`,
        mainEntity: faqs.value.map(f => ({
          '@type': 'Question',
          name: faqQuestion(f),
          acceptedAnswer: { '@type': 'Answer', text: f.answer },
        })),
      })
    }
    return { '@context': 'https://schema.org', '@graph': graph }
  },
})

// OGP / SEO メタタグ。ogUrl は単一ソースの canonicalUrl を使う。
useSeoMeta({
  title: () => t('public.organization.title', { name: organization.value?.name ?? '' }),
  description: () => seoDescription.value,
  ogTitle: () =>
    t('public.organization.title', { name: organization.value?.name ?? '' }),
  ogDescription: () => seoDescription.value,
  ogImage: () => organization.value?.bannerUrl ?? organization.value?.iconUrl ?? '',
  ogType: 'website',
  ogUrl: () => canonicalUrl.value,
  twitterCard: 'summary_large_image',
  twitterTitle: () =>
    t('public.organization.title', { name: organization.value?.name ?? '' }),
  twitterDescription: () => seoDescription.value,
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

    <!-- F19.1 Phase 7: タイムライン投稿セクション（timelinePostsPublic = true の場合のみ） -->
    <section
      v-if="organization.timelinePostsPublic"
      data-testid="timeline-posts-section"
      aria-labelledby="public-org-timeline-heading"
      class="space-y-4"
    >
      <div class="flex items-center justify-between">
        <h2 id="public-org-timeline-heading" class="text-xl font-bold">
          {{ t('public.timeline.title') }}
        </h2>
        <span v-if="timelineTotalElements > 0" class="text-sm text-surface-500">
          {{ t('public.posts.totalCount', { n: timelineTotalElements }) }}
        </span>
      </div>

      <p v-if="timelinePosts.length === 0" class="rounded-lg bg-surface-50 p-6 text-center text-sm text-surface-500 dark:bg-surface-800">
        {{ t('public.timeline.empty') }}
      </p>

      <div v-else class="flex flex-col gap-4">
        <div
          v-for="post in timelinePosts"
          :key="post.id"
          data-testid="timeline-post-item"
          class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
        >
          <div class="mb-2 flex items-center gap-2">
            <img
              v-if="post.authorIconUrl"
              :src="post.authorIconUrl"
              :alt="post.authorDisplayName"
              class="size-8 rounded-full object-cover"
            >
            <span class="text-sm font-medium">{{ post.authorDisplayName }}</span>
            <span class="ml-auto text-xs text-surface-400">{{ post.createdAt }}</span>
          </div>
          <p class="text-sm">{{ post.content }}</p>
        </div>
      </div>

      <nav v-if="timelineTotalPages > 1" class="flex items-center justify-between pt-2" aria-label="timeline-pagination">
        <Button
          :disabled="timelineCurrentPage <= 0"
          severity="secondary"
          outlined
          size="small"
          :label="t('public.posts.prev')"
          @click="goTimelinePage(timelineCurrentPage - 1)"
        />
        <span class="text-sm text-surface-500">
          {{ t('public.posts.page', { page: timelineCurrentPage + 1, total: timelineTotalPages }) }}
        </span>
        <Button
          :disabled="timelineCurrentPage >= timelineTotalPages - 1"
          severity="secondary"
          outlined
          size="small"
          :label="t('public.posts.next')"
          @click="goTimelinePage(timelineCurrentPage + 1)"
        />
      </nav>
    </section>

    <!-- F19.1 Phase 7: イベントセクション（publicEventsEnabled = true の場合のみ） -->
    <section
      v-if="organization.publicEventsEnabled"
      data-testid="public-events-section"
      aria-labelledby="public-org-events-heading"
      class="space-y-4"
    >
      <div class="flex items-center justify-between">
        <h2 id="public-org-events-heading" class="text-xl font-bold">
          {{ t('public.events.title') }}
        </h2>
        <span v-if="eventsTotalElements > 0" class="text-sm text-surface-500">
          {{ t('public.posts.totalCount', { n: eventsTotalElements }) }}
        </span>
      </div>

      <p v-if="events.length === 0" class="rounded-lg bg-surface-50 p-6 text-center text-sm text-surface-500 dark:bg-surface-800">
        {{ t('public.events.empty') }}
      </p>

      <div v-else class="flex flex-col gap-4">
        <div
          v-for="event in events"
          :key="event.id"
          data-testid="public-event-item"
          class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
        >
          <h3 class="mb-1 text-base font-semibold">{{ event.title }}</h3>
          <div class="flex flex-wrap gap-x-4 gap-y-1 text-xs text-surface-500">
            <span>{{ event.startDate }}</span>
            <span v-if="event.endDate">〜 {{ event.endDate }}</span>
            <span v-if="event.location">{{ event.location }}</span>
          </div>
          <p v-if="event.description" class="mt-2 text-sm">{{ event.description }}</p>
        </div>
      </div>

      <nav v-if="eventsTotalPages > 1" class="flex items-center justify-between pt-2" aria-label="events-pagination">
        <Button
          :disabled="eventCurrentPage <= 0"
          severity="secondary"
          outlined
          size="small"
          :label="t('public.posts.prev')"
          @click="goEventsPage(eventCurrentPage - 1)"
        />
        <span class="text-sm text-surface-500">
          {{ t('public.posts.page', { page: eventCurrentPage + 1, total: eventsTotalPages }) }}
        </span>
        <Button
          :disabled="eventCurrentPage >= eventsTotalPages - 1"
          severity="secondary"
          outlined
          size="small"
          :label="t('public.posts.next')"
          @click="goEventsPage(eventCurrentPage + 1)"
        />
      </nav>
    </section>

    <!-- F21.1 §5.5 FAQ駆動GEO: よくあるご質問（回答済み FAQ が 1 件以上のときのみ表示）。
         可視内容は FAQPage JSON-LD の mainEntity と完全一致させる。 -->
    <section
      v-if="faqs.length > 0"
      data-testid="public-faq-section"
      aria-labelledby="public-org-faq-heading"
      class="space-y-4"
    >
      <h2 id="public-org-faq-heading" class="text-xl font-bold">
        {{ t('faq.public.title') }}
      </h2>

      <Accordion multiple>
        <AccordionPanel
          v-for="(item, index) in faqs"
          :key="index"
          :value="String(index)"
          data-testid="public-faq-item"
        >
          <AccordionHeader>
            <span class="text-sm font-medium" data-testid="public-faq-question">
              {{ faqQuestion(item) }}
            </span>
          </AccordionHeader>
          <AccordionContent>
            <p class="whitespace-pre-line text-sm text-surface-700 dark:text-surface-200" data-testid="public-faq-answer">
              {{ item.answer }}
            </p>
          </AccordionContent>
        </AccordionPanel>
      </Accordion>
    </section>

    <LoginCtaCard scope-kind="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
