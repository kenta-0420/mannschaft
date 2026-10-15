<script setup lang="ts">
import type { BlogSeries, BlogPostResponse } from '~/types/cms'
import { blogPostReadRoute } from '~/utils/blogPostRoute'

const props = defineProps<{
  series: BlogSeries
  currentPostId: number
}>()

const { getPosts } = useBlogApi()
const { error: showError } = useNotification()

const seriesPosts = ref<BlogPostResponse[]>([])
const loading = ref(false)

const currentIndex = computed(() =>
  seriesPosts.value.findIndex((p) => p.id === props.currentPostId),
)
const prevPost = computed<BlogPostResponse | null>(() =>
  currentIndex.value > 0 ? (seriesPosts.value[currentIndex.value - 1] ?? null) : null,
)
const nextPost = computed<BlogPostResponse | null>(() =>
  currentIndex.value >= 0 && currentIndex.value < seriesPosts.value.length - 1
    ? (seriesPosts.value[currentIndex.value + 1] ?? null)
    : null,
)
// 記事詳細はスコープ指定が必須（スコープ無しの URL は 404）。記事のスコープから閲覧ルートを組み立てる。
const prevRoute = computed(() => (prevPost.value ? blogPostReadRoute(prevPost.value) : null))
const nextRoute = computed(() => (nextPost.value ? blogPostReadRoute(nextPost.value) : null))

async function loadSeriesPosts() {
  if (!props.series?.id) return
  loading.value = true
  try {
    const res = await getPosts({ series_id: props.series.id, page: 0, size: 100 })
    seriesPosts.value = res.data
  } catch {
    showError('シリーズ記事の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => loadSeriesPosts())
</script>

<template>
  <div
    v-if="prevRoute || nextRoute"
    class="mt-10 border-t border-surface-200 pt-6 dark:border-surface-700"
  >
    <p class="mb-3 text-xs font-semibold uppercase tracking-wider text-surface-400">
      {{ $t('blog.post.series') }}: {{ series.title }}
    </p>
    <div class="flex items-stretch justify-between gap-4">
      <!-- 前の記事 -->
      <NuxtLink
        v-if="prevPost && prevRoute"
        :to="prevRoute"
        class="group flex flex-1 items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-4 py-3 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
      >
        <i class="pi pi-chevron-left text-surface-400 group-hover:text-primary-500" />
        <div class="min-w-0">
          <p class="mb-0.5 text-xs text-surface-400">前の記事</p>
          <p class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ prevPost.content?.title }}
          </p>
        </div>
      </NuxtLink>
      <div v-else class="flex-1" />

      <!-- 次の記事 -->
      <NuxtLink
        v-if="nextPost && nextRoute"
        :to="nextRoute"
        class="group flex flex-1 items-center justify-end gap-3 rounded-lg border border-surface-200 bg-surface-0 px-4 py-3 text-right transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
      >
        <div class="min-w-0">
          <p class="mb-0.5 text-xs text-surface-400">次の記事</p>
          <p class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ nextPost.content?.title }}
          </p>
        </div>
        <i class="pi pi-chevron-right text-surface-400 group-hover:text-primary-500" />
      </NuxtLink>
      <div v-else class="flex-1" />
    </div>
  </div>
</template>
