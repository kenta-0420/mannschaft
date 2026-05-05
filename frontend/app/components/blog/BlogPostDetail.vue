<script setup lang="ts">
import type { BlogPostResponse, BlogTag } from '~/types/cms'

const props = defineProps<{
  post: BlogPostResponse
}>()

const emit = defineEmits<{
  tagClick: [tag: BlogTag]
}>()

const { renderMarkdown } = useMarkdownRenderer()

const renderedBody = computed(() => {
  return props.post.body ? renderMarkdown(props.post.body) : ''
})

const formattedPublishedAt = computed(() => {
  if (!props.post.publishedAt) return null
  return new Date(props.post.publishedAt).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
})
</script>

<template>
  <article class="mx-auto max-w-3xl">
    <!-- カバー画像 -->
    <img
      v-if="post.coverImageUrl"
      :src="post.coverImageUrl"
      :alt="post.title"
      class="mb-8 h-64 w-full rounded-xl object-cover shadow-sm"
    >

    <!-- タイトル -->
    <h1 class="mb-4 text-3xl font-bold leading-tight text-surface-900 dark:text-surface-0">
      {{ post.title }}
    </h1>

    <!-- メタ情報 -->
    <div class="mb-6 flex flex-wrap items-center gap-4 text-sm text-surface-500">
      <!-- 著者 -->
      <div class="flex items-center gap-2">
        <img
          v-if="post.author.avatarUrl"
          :src="post.author.avatarUrl"
          :alt="post.author.displayName"
          class="h-7 w-7 rounded-full object-cover"
        >
        <span v-else class="flex h-7 w-7 items-center justify-center rounded-full bg-surface-200 text-xs">
          {{ post.author.displayName.charAt(0) }}
        </span>
        <span class="font-medium text-surface-700 dark:text-surface-200">
          {{ post.author.displayName }}
        </span>
      </div>

      <!-- 公開日 -->
      <div v-if="formattedPublishedAt" class="flex items-center gap-1">
        <i class="pi pi-calendar text-xs" />
        <span>{{ $t('blog.post.publishedAt') }}: {{ formattedPublishedAt }}</span>
      </div>

      <!-- 閲覧数 -->
      <div v-if="post.viewCount" class="flex items-center gap-1">
        <i class="pi pi-eye text-xs" />
        <span>{{ post.viewCount }}</span>
      </div>
    </div>

    <!-- シリーズ情報 -->
    <div
      v-if="post.seriesName"
      class="mb-6 flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 dark:border-blue-800 dark:bg-blue-950"
    >
      <i class="pi pi-list text-blue-500" />
      <span class="text-sm text-blue-700 dark:text-blue-300">
        {{ $t('blog.post.series') }}: <strong>{{ post.seriesName }}</strong>
        <span v-if="post.seriesOrder" class="ml-1 text-blue-500">
          ({{ post.seriesOrder }}話目)
        </span>
      </span>
    </div>

    <!-- タグ一覧 -->
    <div v-if="post.tags && post.tags.length > 0" class="mb-6 flex flex-wrap gap-2">
      <button
        v-for="tag in post.tags"
        :key="tag.id"
        class="rounded-full border border-surface-300 bg-surface-100 px-3 py-1 text-xs text-surface-600 transition-colors hover:bg-surface-200 dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700"
        @click="emit('tagClick', tag)"
      >
        #{{ tag.name }}
      </button>
    </div>

    <!-- 本文（renderedBody は marked + sanitizeHtml でサニタイズ済み） -->
    <!-- eslint-disable vue/no-v-html -->
    <div
      v-if="renderedBody"
      class="prose prose-lg max-w-none dark:prose-invert"
      v-html="renderedBody"
    />
    <!-- eslint-enable vue/no-v-html -->
    <p v-else class="text-surface-400 italic">
      {{ $t('blog.post.noPost') }}
    </p>
  </article>
</template>
