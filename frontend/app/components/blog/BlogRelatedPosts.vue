<script setup lang="ts">
import type { BlogTag, BlogPostResponse } from '~/types/cms'

const props = defineProps<{
  tags: BlogTag[]
  currentPostId: number
}>()

const { getPosts } = useBlogApi()
const { error: showError } = useNotification()
const { relativeTime } = useRelativeTime()

const relatedPosts = ref<BlogPostResponse[]>([])
const loading = ref(false)

async function loadRelatedPosts() {
  if (!props.tags || props.tags.length === 0) return
  loading.value = true
  try {
    const firstTagId = props.tags[0]?.id
    if (!firstTagId) return
    const res = await getPosts({ tag_id: firstTagId, page: 0, size: 4 })
    relatedPosts.value = res.data
      .filter((p) => p.id !== props.currentPostId)
      .slice(0, 3)
  } catch {
    showError('関連記事の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => loadRelatedPosts())
</script>

<template>
  <section
    v-if="relatedPosts.length > 0"
    class="mt-10 border-t border-surface-200 pt-6 dark:border-surface-700"
  >
    <h2 class="mb-4 text-base font-semibold text-surface-800 dark:text-surface-100">
      {{ $t('blog.post.relatedPosts') }}
    </h2>

    <div v-if="loading" class="flex justify-center py-6">
      <ProgressSpinner style="width: 32px; height: 32px" />
    </div>

    <div v-else class="grid gap-4 sm:grid-cols-3">
      <NuxtLink
        v-for="post in relatedPosts"
        :key="post.id"
        :to="`/blog/posts/${post.slug}`"
        class="group overflow-hidden rounded-xl border border-surface-200 bg-surface-0 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
      >
        <img
          v-if="post.coverImageUrl"
          :src="post.coverImageUrl"
          :alt="post.title"
          class="h-32 w-full object-cover"
        />
        <div class="p-3">
          <p class="mb-1 text-sm font-medium text-surface-800 line-clamp-2 group-hover:text-primary-600 dark:text-surface-100">
            {{ post.title }}
          </p>
          <p class="text-xs text-surface-400">
            {{ relativeTime(post.publishedAt || post.createdAt) }}
          </p>
        </div>
      </NuxtLink>
    </div>
  </section>
</template>
