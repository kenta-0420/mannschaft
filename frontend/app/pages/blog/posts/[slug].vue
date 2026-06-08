<script setup lang="ts">
import type { BlogPostResponse, BlogSeries, BlogTag } from '~/types/cms'
import type { GateCheckResponse } from '~/types/payment'

const route = useRoute()
const slug = route.params.slug as string

const { getPost, addMitayo, removeMitayo } = useBlogApi()
const { checkAccess } = useContentGateApi()
const { handleError } = useErrorHandler()

const post = ref<BlogPostResponse | null>(null)
const loading = ref(true)
const mitayoLoading = ref(false)
const gateLoading = ref(false)
const gateResult = ref<GateCheckResponse | null>(null)

async function loadPost() {
  loading.value = true
  try {
    const res = await getPost(slug)
    post.value = res.data
    // 記事取得後にペイウォール判定（POST = ブログ記事）
    if (post.value?.id) {
      await loadGateCheck(post.value.id)
    }
  } catch (error) {
    handleError(error)
  } finally {
    loading.value = false
  }
}

async function loadGateCheck(postId: number) {
  gateLoading.value = true
  try {
    const res = await checkAccess('POST', postId)
    gateResult.value = res.data
  } catch {
    // ゲートチェック失敗時は fail-safe: accessible=true として扱い通常表示する
    // （未ログイン時の 401 等を考慮。ペイウォールは設定のある記事のみに影響）
    gateResult.value = { accessible: true, titleHidden: false, requiredItems: [] }
  } finally {
    gateLoading.value = false
  }
}

async function handleToggleMitayo() {
  if (!post.value || mitayoLoading.value) return
  mitayoLoading.value = true
  try {
    const res = post.value.stats?.mitayo
      ? await removeMitayo(post.value.id)
      : await addMitayo(post.value.id)
    post.value = {
      ...post.value,
      stats: {
        ...post.value.stats,
        viewCount: post.value.stats?.viewCount ?? 0,
        readingTimeMinutes: post.value.stats?.readingTimeMinutes ?? null,
        mitayo: res.data.mitayo,
        mitayoCount: res.data.mitayoCount,
      },
    }
  } catch (error) {
    handleError(error)
  } finally {
    mitayoLoading.value = false
  }
}

// タイトル非表示（titleHidden=true）のときは OGP でも非表示にする
const effectiveTitle = computed<string>(() => {
  if (gateResult.value?.titleHidden && !gateResult.value?.accessible) {
    return 'ブログ記事'
  }
  return post.value?.content?.title ?? 'ブログ記事'
})

useHead(() => ({
  title: effectiveTitle.value,
  meta: [
    {
      name: 'description',
      content:
        gateResult.value?.titleHidden && !gateResult.value?.accessible
          ? ''
          : (post.value?.content?.excerpt ?? post.value?.content?.title ?? ''),
    },
    {
      property: 'og:title',
      content:
        gateResult.value?.titleHidden && !gateResult.value?.accessible
          ? ''
          : (post.value?.content?.title ?? ''),
    },
    {
      property: 'og:description',
      content:
        gateResult.value?.titleHidden && !gateResult.value?.accessible
          ? ''
          : (post.value?.content?.excerpt ?? ''),
    },
    ...(post.value?.content?.coverImageUrl &&
    !(gateResult.value?.titleHidden && !gateResult.value?.accessible)
      ? [{ property: 'og:image', content: post.value.content.coverImageUrl }]
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
      <!-- ペイウォール判定中 or ロック時: PaywallLock でコンテンツをラップ -->
      <PaymentPaywallLock :loading="gateLoading" :gate-result="gateResult">
        <BlogPostDetail :post="post" @tag-click="onTagClick" />

        <div class="mt-4 flex justify-center">
          <TimelineMitayoButton
            :mitayo="post.stats?.mitayo ?? false"
            :mitayo-count="post.stats?.mitayoCount ?? 0"
            :loading="mitayoLoading"
            @toggle="handleToggleMitayo"
          />
        </div>

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
      </PaymentPaywallLock>
    </template>

    <DashboardEmptyState
      v-else
      icon="pi pi-book"
      :message="$t('blog.post.noPost')"
    />
  </div>
</template>
