<script setup lang="ts">
import type { PublicPostDetail } from '~/types/public'
import { sanitizeHtml } from '~/utils/sanitizeHtml'

/**
 * F19.1 公開投稿詳細表示。
 *
 * タイトル / 本文 HTML / 投稿者識別 / 投稿日 を表示。
 * bodyHtml は BE で既にサニタイズ済みだが、Defense in Depth で
 * フロント側でも {@link sanitizeHtml} を通す。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.3 / §10.7
 */
const props = defineProps<{
  post: PublicPostDetail
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

const sanitizedBody = computed(() => sanitizeHtml(props.post.bodyHtml ?? ''))
</script>

<template>
  <article class="space-y-6">
    <header class="space-y-3 border-b border-surface-200 pb-4 dark:border-surface-700">
      <h1 class="text-3xl font-bold text-surface-900 dark:text-surface-50">
        {{ post.title }}
      </h1>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <PublicAuthorIdentityBadge :author="post.author" :size="36" />
        <time
          v-if="publishedDate"
          :datetime="post.publishedAt"
          class="text-sm text-surface-500"
        >
          {{ t('public.posts.publishedAt', { date: publishedDate }) }}
        </time>
      </div>
    </header>

    <!-- 本文（サニタイズ済み HTML） -->
    <div
      class="prose prose-sm max-w-none text-surface-800 dark:prose-invert dark:text-surface-100"
      data-testid="public-post-body"
      v-html="sanitizedBody"
    />
  </article>
</template>
