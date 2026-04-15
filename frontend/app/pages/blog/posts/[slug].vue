<script setup lang="ts">
import type { BlogPostResponse, BlogSeries, BlogTag } from '~/types/cms'

const route = useRoute()
const slug = route.params.slug as string

const { getPost } = useBlogApi()
const { handleError } = useErrorHandler()

const post = ref<BlogPostResponse | null>(null)
const loading = ref(true)

async function loadPost() {
  loading.value = true
  try {
    const res = await getPost(slug)
    post.value = res.data
  } catch (error) {
    handleError(error)
  } finally {
    loading.value = false
  }
}

useHead(() => ({
  title: post.value?.title ?? 'ブログ記事',
  meta: [
    {
      name: 'description',
      content: post.value?.excerpt ?? post.value?.title ?? '',
    },
    { property: 'og:title', content: post.value?.title ?? '' },
    { property: 'og:description', content: post.value?.excerpt ?? '' },
    ...(post.value?.coverImageUrl
      ? [{ property: 'og:image', content: post.value.coverImageUrl }]
      : []),
  ],
}))

const seriesForNav = computed<BlogSeries | null>(() => {
  if (!post.value?.seriesId || !post.value?.seriesName) return null
  return {
    id: post.value.seriesId,
    title: post.value.seriesName,
    description: null,
    postCount: 0,
    createdAt: '',
  }
})

const tagsForRelated = computed<BlogTag[]>(() => {
  if (!post.value?.tags) return []
  return post.value.tags.map((t) => ({ id: t.id, name: t.name, postCount: 0 }))
})

function onTagClick(tag: BlogTag) {
  navigateTo(`/blog?tag=${tag.id}`)
}

onMounted(() => loadPost())
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 py-8">
    <div class="mb-6">
      <BackButton />
    </div>

    <PageLoading v-if="loading" />

    <template v-else-if="post">
      <BlogPostDetail :post="post" @tag-click="onTagClick" />

      <BlogSeriesNav
        v-if="seriesForNav"
        :series="seriesForNav"
        :current-post-id="post.id"
        class="mt-8"
      />

      <BlogRelatedPosts
        :tags="tagsForRelated"
        :current-post-id="post.id"
        class="mt-8"
      />
    </template>

    <DashboardEmptyState
      v-else
      icon="pi pi-book"
      :message="$t('blog.post.noPost')"
    />
  </div>
</template>
