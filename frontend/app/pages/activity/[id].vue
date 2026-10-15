<script setup lang="ts">
import type { PublicActivityResponse } from '~/types/activity'

/**
 * F06.4 公開活動記録ページ（SNS シェア / 検索エンジン向け）。
 *
 * 設計書: docs/features/F06.4_activity_records.md「公開ページの SSR 要件」/
 *         docs/features/F19.1_public_pages_identity_disclosure.md §9.2
 *
 * 【SSR 必須】旧実装は `onMounted` の CSR 専用データ取得だったため、
 * SSR HTML には本文も OGP も一切載らなかった（実測: `<title>` すら無い空の骨組みのみ）。
 * 原因は 2 つあり、両方を直さないと SSR 化しない:
 *   1. `layouts/default.vue` が `v-if="!isMounted"` でマウント前はスピナーだけを描画する
 *      （＝どのページでも SSR 出力が空になる）
 *   2. データ取得が `onMounted`（サーバー側では走らない）
 * そこで他の公開ページ（`pages/public/**`）と同じく `layout: 'public'` +
 * `useAsyncData` + `useSeoMeta` / `useSeoPublicPage` に揃えた。
 *
 * これは SEO だけの都合ではない。**SNS のクローラ（LINE / X / Facebook）は
 * JavaScript を実行しない**ため、CSR のままでは OGP タグが永久に読まれず、
 * シェア機能を主目的とする本ページの OGP が丸ごと死んでいた。
 */
definePageMeta({
  // 認証不要ページ（公開活動記録は誰でも閲覧可能）
  auth: false,
  // 未ログイン向けの簡素なヘッダー / フッター。default レイアウトと違い
  // SSR 出力を握り潰さない（上記コメント参照）。
  layout: 'public',
})

const route = useRoute()
const { t } = useI18n()
const { fetchPublicActivity } = useActivityPublicApi()

const rawId = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
const activityId = Number(rawId)

if (!Number.isFinite(activityId) || activityId <= 0) {
  throw createError({
    statusCode: 404,
    statusMessage: t('public.error.notFound'),
    fatal: true,
  })
}

// SSR 段でデータを取得する。fetchPublicActivity は 404 のとき例外ではなく null を返す仕様
// （非公開・DRAFT・削除済み・親スコープ非公開はすべて BE 側で 404 に倒れる）。
const { data: activity, error: activityError } = await useAsyncData<PublicActivityResponse | null>(
  `public-activity-${activityId}`,
  () => fetchPublicActivity(activityId),
)

if (activityError.value || !activity.value) {
  // 存在秘匿: 理由を区別せず一律 404（BE の PUBLIC_013 と同じ方針）
  throw createError({
    statusCode: 404,
    statusMessage: t('public.error.notFound'),
    fatal: true,
  })
}

const canonicalPath = `/activity/${activityId}`

const descriptionForOgp = computed(
  () => activity.value?.description ?? activity.value?.title ?? '',
)

useSeoMeta({
  title: () => activity.value?.title ?? '',
  description: () => descriptionForOgp.value,
  ogTitle: () => activity.value?.title ?? '',
  ogDescription: () => descriptionForOgp.value,
  // NOTE: og:image は設定しない。公開活動記録 API（PublicActivityDetail）は
  // 御裁可済み 8 項目のみを返し、画像 URL を含まないため。
  ogImage: '',
  ogType: 'article',
  twitterCard: 'summary',
  twitterTitle: () => activity.value?.title ?? '',
  twitterDescription: () => descriptionForOgp.value,
})

// canonical + hreflang 6言語 + JSON-LD。canonicalUrl は本 composable が単一ソース
// （ページ側で apiBase からホストを組み立てるとホスト不整合が起きるため流用する）。
const { canonicalUrl } = useSeoPublicPage({
  canonicalPath,
  title: () => activity.value?.title ?? '',
  description: () => descriptionForOgp.value,
  jsonLd: () =>
    activity.value
      ? {
          '@context': 'https://schema.org',
          '@type': 'Event',
          name: activity.value.title,
          startDate: activity.value.activityDate,
          description: activity.value.description ?? undefined,
          organizer: activity.value.scopeRef?.scopeName
            ? {
                '@type': 'Organization',
                name: activity.value.scopeRef.scopeName,
              }
            : undefined,
        }
      : undefined,
})

// 公開 URL（シェアパネルに渡す用）。SSR / CSR どちらでも同じ絶対 URL になる。
const shareUrl = computed(() => canonicalUrl.value)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <template v-if="activity">
      <div
        class="rounded-lg border border-surface-200 bg-white p-6 dark:border-surface-700 dark:bg-surface-800"
      >
        <!-- スコープ名（チーム or 組織）: BE の PublicScopeRef から取得する -->
        <p v-if="activity.scopeRef?.scopeName" class="mb-2 text-sm text-surface-400">
          {{ activity.scopeRef.scopeName }}
        </p>

        <!-- タイトル -->
        <h1 class="mb-3 text-2xl font-bold text-surface-900 dark:text-surface-50">
          {{ activity.title }}
        </h1>

        <!--
          日付
          NOTE: 開催場所（location）・参加人数・画像・カスタムフィールドは表示しない。
          公開活動記録 API は御裁可済み 8 項目のみを返し、これらは禁則フィールドとして
          意図的に含まれていない（BE: PublicActivityDetail の Javadoc 参照）。
        -->
        <div class="mb-4 flex flex-wrap gap-3 text-sm text-surface-500">
          <span>
            <i class="pi pi-calendar mr-1" />{{ activity.activityDate }}
          </span>
        </div>

        <!-- 本文 -->
        <p
          v-if="activity.description"
          class="whitespace-pre-wrap text-sm leading-relaxed text-surface-700 dark:text-surface-300"
        >
          {{ activity.description }}
        </p>
      </div>

      <!-- シェアパネル -->
      <ActivitySharePanel
        :activity-id="activityId"
        :title="activity.title"
        :url="shareUrl"
        class="mt-6"
      />
    </template>
  </div>
</template>
