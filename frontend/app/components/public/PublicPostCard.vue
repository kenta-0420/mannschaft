<script setup lang="ts">
import type { PublicPostSummary } from '~/types/public'

/**
 * F19.1 公開投稿カード（一覧用）。
 *
 * タイトル / 抜粋 / 投稿者識別 / 投稿日 を表示。
 * クリックで投稿詳細ページへ遷移する。NuxtLink でラップする。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.2 / §8.2
 */
const props = defineProps<{
  post: PublicPostSummary
  /** 詳細ページへのリンク先 URL（親側で組み立て） */
  detailHref: string
}>()

const { t } = useI18n()

const publishedDate = computed(() => {
  if (!props.post.publishedAt) return ''
  try {
    return new Date(props.post.publishedAt).toLocaleDateString()
  } catch {
    return props.post.publishedAt
  }
})
</script>

<template>
  <article
    class="rounded-lg border border-surface-200 bg-white p-4 transition hover:border-primary/40 hover:shadow-sm dark:border-surface-700 dark:bg-surface-800"
    data-testid="public-post-card"
  >
    <NuxtLink :to="detailHref" class="block focus:outline-none focus:ring-2 focus:ring-primary">
      <h3 class="text-lg font-bold text-surface-900 hover:text-primary dark:text-surface-50">
        {{ post.title }}
      </h3>
      <p
        v-if="post.excerpt"
        class="mt-2 line-clamp-3 text-sm text-surface-600 dark:text-surface-300"
      >
        {{ post.excerpt }}
      </p>
    </NuxtLink>
    <div class="mt-3 flex items-center justify-between border-t border-surface-100 pt-3 dark:border-surface-700">
      <PublicAuthorIdentityBadge :author="post.author" :size="28" />
      <time
        v-if="publishedDate"
        :datetime="post.publishedAt"
        class="text-xs text-surface-500"
      >
        {{ t('public.posts.publishedAt', { date: publishedDate }) }}
      </time>
    </div>
  </article>
</template>
