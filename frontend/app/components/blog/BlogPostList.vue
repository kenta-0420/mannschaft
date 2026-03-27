<script setup lang="ts">
import type { BlogPostResponse } from '~/types/cms'

const props = defineProps<{
  scopeType?: string
  scopeId?: number
}>()

const emit = defineEmits<{
  select: [post: BlogPostResponse]
  create: []
}>()

const { getPosts } = useBlogApi()
const { showError } = useNotification()
const { relativeTime } = useRelativeTime()

const posts = ref<BlogPostResponse[]>([])
const loading = ref(false)

async function loadPosts() {
  loading.value = true
  try {
    const res = await getPosts({ scope_type: props.scopeType, scope_id: props.scopeId, page: 0, size: 20 })
    posts.value = res.data
  } catch {
    showError('記事一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'DRAFT': return 'bg-surface-100 text-surface-600'
    case 'PUBLISHED': return 'bg-green-100 text-green-700'
    case 'SCHEDULED': return 'bg-blue-100 text-blue-700'
    default: return 'bg-surface-100'
  }
}

onMounted(() => loadPosts())
defineExpose({ refresh: loadPosts })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">記事</h2>
      <Button label="新規作成" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <button
        v-for="post in posts"
        :key="post.id"
        class="overflow-hidden rounded-xl border border-surface-200 bg-surface-0 text-left transition-shadow hover:shadow-md"
        @click="emit('select', post)"
      >
        <img v-if="post.coverImageUrl" :src="post.coverImageUrl" class="h-40 w-full object-cover" />
        <div class="p-4">
          <div class="mb-2 flex flex-wrap items-center gap-2">
            <span :class="getStatusClass(post.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{ post.status }}</span>
            <span v-for="tag in post.tags.slice(0, 3)" :key="tag.id" class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500">{{ tag.name }}</span>
          </div>
          <h3 class="mb-1 text-sm font-semibold line-clamp-2">{{ post.title }}</h3>
          <p v-if="post.excerpt" class="mb-2 text-xs text-surface-400 line-clamp-2">{{ post.excerpt }}</p>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <span>{{ post.author.displayName }}</span>
            <span>{{ relativeTime(post.publishedAt || post.createdAt) }}</span>
            <span v-if="post.viewCount"><i class="pi pi-eye" /> {{ post.viewCount }}</span>
          </div>
        </div>
      </button>
    </div>

    <div v-if="!loading && posts.length === 0" class="py-12 text-center">
      <i class="pi pi-book mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">記事がありません</p>
    </div>
  </div>
</template>
