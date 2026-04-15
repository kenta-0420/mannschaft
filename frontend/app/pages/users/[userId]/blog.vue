<script setup lang="ts">
import type { BlogPostResponse, BlogTag } from '~/types/cms'

const route = useRoute()
const userId = Number(route.params.userId)

const { getUserPosts } = useBlogApi()
const { handleError } = useErrorHandler()
const { relativeTime } = useRelativeTime()

const posts = ref<BlogPostResponse[]>([])
const loading = ref(true)
const selectedTagIds = ref<number[]>([])

// 全タグ一覧（投稿から収集）
const allTags = computed<BlogTag[]>(() => {
  const tagMap = new Map<number, BlogTag>()
  for (const post of posts.value) {
    for (const t of post.tags) {
      if (!tagMap.has(t.id)) tagMap.set(t.id, { id: t.id, name: t.name, postCount: 0 })
    }
  }
  return Array.from(tagMap.values())
})

// タグフィルタ適用後の記事一覧
const filteredPosts = computed<BlogPostResponse[]>(() => {
  if (selectedTagIds.value.length === 0) return posts.value
  return posts.value.filter((post) =>
    selectedTagIds.value.every((tagId) => post.tags.some((t) => t.id === tagId)),
  )
})

async function loadPosts() {
  loading.value = true
  try {
    const res = await getUserPosts(userId, { page: 0, size: 50 })
    posts.value = res.data
  } catch (error) {
    handleError(error)
  } finally {
    loading.value = false
  }
}

function toggleTag(tagId: number) {
  const idx = selectedTagIds.value.indexOf(tagId)
  if (idx >= 0) {
    selectedTagIds.value.splice(idx, 1)
  } else {
    selectedTagIds.value.push(tagId)
  }
}

onMounted(() => loadPosts())
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 py-8">
    <div class="mb-6 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="$t('blog.post.published')" />
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- タグフィルタ -->
      <div v-if="allTags.length > 0" class="mb-6 flex flex-wrap gap-2">
        <button
          v-for="tag in allTags"
          :key="tag.id"
          :class="[
            'rounded-full border px-3 py-1 text-xs transition-colors',
            selectedTagIds.includes(tag.id)
              ? 'border-primary-500 bg-primary-500 text-white'
              : 'border-surface-300 bg-surface-100 text-surface-600 hover:bg-surface-200 dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700',
          ]"
          @click="toggleTag(tag.id)"
        >
          #{{ tag.name }}
        </button>
      </div>

      <!-- 記事一覧 -->
      <div v-if="filteredPosts.length > 0" class="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        <NuxtLink
          v-for="post in filteredPosts"
          :key="post.id"
          :to="`/users/${userId}/blog/posts/${post.slug}`"
          class="group overflow-hidden rounded-xl border border-surface-200 bg-surface-0 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
        >
          <img
            v-if="post.coverImageUrl"
            :src="post.coverImageUrl"
            :alt="post.title"
            class="h-40 w-full object-cover"
          />
          <div class="p-4">
            <div class="mb-2 flex flex-wrap gap-1">
              <span
                v-for="tag in post.tags.slice(0, 3)"
                :key="tag.id"
                class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-800"
              >
                #{{ tag.name }}
              </span>
            </div>
            <h3 class="mb-1 text-sm font-semibold text-surface-800 line-clamp-2 group-hover:text-primary-600 dark:text-surface-100">
              {{ post.title }}
            </h3>
            <p v-if="post.excerpt" class="mb-2 text-xs text-surface-400 line-clamp-2">
              {{ post.excerpt }}
            </p>
            <div class="flex items-center gap-2 text-xs text-surface-400">
              <span>{{ relativeTime(post.publishedAt || post.createdAt) }}</span>
              <span v-if="post.viewCount"><i class="pi pi-eye" /> {{ post.viewCount }}</span>
            </div>
          </div>
        </NuxtLink>
      </div>

      <DashboardEmptyState
        v-else
        icon="pi pi-book"
        :message="$t('blog.post.noPost')"
      />
    </template>
  </div>
</template>
