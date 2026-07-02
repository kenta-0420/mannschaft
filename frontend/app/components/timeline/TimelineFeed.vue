<script setup lang="ts">
import type { TimelinePostResponse, TimelineScopeType } from '~/types/timeline'

const props = defineProps<{
  /**
   * スコープ種別。myFeed=true（個人集約タイムライン）の場合は不要。
   * 単一スコープ表示時は必須（TEAM/ORGANIZATION/PUBLIC/VILLAGE）。
   */
  scopeType?: TimelineScopeType
  /** TEAM/ORGANIZATION は数値ID、VILLAGE は UUID 文字列 */
  scopeId?: string | number
  /**
   * 個人ダッシュボード集約タイムライン（所属 team/org 横断）モード。
   * true の場合 GET /api/v1/timeline/my を使い、scopeType/scopeId は不要・pinned は常に空。
   */
  myFeed?: boolean
  canPin?: boolean
  canDeleteOthers?: boolean
  /**
   * ダッシュボードのウィジェット内など、狭い枠で先頭 N 件だけ表示したい場合の上限。
   * 指定時は追加ロード（「もっと読む」）を無効化し、一覧ページ側へ委譲する。
   */
  limit?: number
}>()

const emit = defineEmits<{
  clickPost: [postId: number]
}>()

const {
  getFeed,
  getMyTimeline,
  addBookmark,
  removeBookmark,
  pinPost,
  deletePost,
  createReply,
  repost,
} = useTimelineApi()
const { showSuccess, showError } = useNotification()

const pinnedPosts = ref<TimelinePostResponse[]>([])
const posts = ref<TimelinePostResponse[]>([])

/** limit 指定時は先頭 N 件だけ描画（ダッシュボード等の狭い枠向け）。未指定なら全件。 */
const displayPosts = computed(() =>
  props.limit != null ? posts.value.slice(0, props.limit) : posts.value,
)
const nextCursor = ref<number | null>(null)
const hasNext = ref(false)
const loading = ref(false)
const initialLoaded = ref(false)

// --- 返信フォーム ---
const replyTargetId = ref<number | null>(null)
const replyContent = ref('')
const replySubmitting = ref(false)

// --- リポスト確認 ---
const repostTargetId = ref<number | null>(null)
const repostSubmitting = ref(false)

async function loadFeed(cursor?: number) {
  loading.value = true
  try {
    // myFeed モード: 所属 team/org 横断の個人集約タイムライン（pinned は常に空）。
    // 単一スコープモード: 従来通り scopeType/scopeId でフィード取得。
    const res = props.myFeed
      ? await getMyTimeline(cursor)
      : await getFeed({
          scopeType: props.scopeType ?? 'PUBLIC',
          scopeId: props.scopeId,
          cursor,
        })
    if (!cursor) {
      pinnedPosts.value = res.data.pinned
      posts.value = res.data.posts
    } else {
      posts.value.push(...res.data.posts)
    }
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
    initialLoaded.value = true
  } catch {
    showError('タイムラインの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function loadMore() {
  if (nextCursor.value && !loading.value) {
    loadFeed(nextCursor.value)
  }
}

function onMitayoToggled(postId: number, mitayo: boolean, mitayoCount: number) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (!post) return
  post.mitayo = mitayo
  post.mitayoCount = mitayoCount
  if (post.stats) post.stats.reactionCount = mitayoCount
}

async function onBookmark(postId: number) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (!post) return
  try {
    if (post.isBookmarked) {
      await removeBookmark(postId)
      post.isBookmarked = false
    } else {
      await addBookmark(postId)
      post.isBookmarked = true
    }
  } catch {
    showError('ブックマークに失敗しました')
  }
}

async function onPin(postId: number) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (!post) return
  try {
    await pinPost(postId)
    showSuccess(post.content?.isPinned ? 'ピン解除しました' : 'ピン留めしました')
    refresh()
  } catch {
    showError('ピン操作に失敗しました')
  }
}

async function onDelete(postId: number) {
  try {
    await deletePost(postId)
    posts.value = posts.value.filter((p) => p.id !== postId)
    pinnedPosts.value = pinnedPosts.value.filter((p) => p.id !== postId)
    showSuccess('投稿を削除しました')
  } catch {
    showError('削除に失敗しました')
  }
}

// --- 返信 ---
function onReply(postId: number) {
  replyTargetId.value = postId
  replyContent.value = ''
}

function cancelReply() {
  replyTargetId.value = null
  replyContent.value = ''
}

async function submitReply() {
  if (!replyTargetId.value || !replyContent.value.trim()) return
  replySubmitting.value = true
  try {
    await createReply(replyTargetId.value, replyContent.value.trim())
    showSuccess('返信しました')
    replyTargetId.value = null
    replyContent.value = ''
    refresh()
  } catch {
    showError('返信に失敗しました')
  } finally {
    replySubmitting.value = false
  }
}

// --- リポスト ---
function onRepost(postId: number) {
  repostTargetId.value = postId
}

function cancelRepost() {
  repostTargetId.value = null
}

async function confirmRepost() {
  if (!repostTargetId.value) return
  repostSubmitting.value = true
  try {
    await repost(repostTargetId.value)
    showSuccess('リポストしました')
    repostTargetId.value = null
    refresh()
  } catch {
    showError('リポストに失敗しました')
  } finally {
    repostSubmitting.value = false
  }
}

function refresh() {
  loadFeed()
}

onMounted(() => loadFeed())

defineExpose({ refresh })
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- ピン留め投稿 -->
    <TimelinePostCard
      v-for="post in pinnedPosts"
      :key="`pin-${post.id}`"
      :post="post"
      :can-pin="canPin"
      :can-delete-others="canDeleteOthers"
      @mitayo-toggled="onMitayoToggled"
      @bookmark="onBookmark"
      @pin="onPin"
      @delete="onDelete"
      @reply="onReply"
      @repost="onRepost"
      @click-post="(id) => emit('clickPost', id)"
    />

    <!-- 通常投稿 -->
    <TimelinePostCard
      v-for="post in displayPosts"
      :key="post.id"
      :post="post"
      :can-pin="canPin"
      :can-delete-others="canDeleteOthers"
      @mitayo-toggled="onMitayoToggled"
      @bookmark="onBookmark"
      @pin="onPin"
      @delete="onDelete"
      @reply="onReply"
      @repost="onRepost"
      @click-post="(id) => emit('clickPost', id)"
    />

    <!-- 空状態 -->
    <div
      v-if="initialLoaded && posts.length === 0 && pinnedPosts.length === 0"
      class="py-12 text-center"
    >
      <i class="pi pi-comments mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">まだ投稿がありません</p>
    </div>

    <!-- もっと読む（limit 指定時は追加ロードを無効化し、一覧ページ側へ委譲） -->
    <div v-if="hasNext && limit == null" class="flex justify-center py-4">
      <Button label="もっと読む" text :loading="loading" @click="loadMore" />
    </div>

    <!-- ローディング -->
    <div v-if="loading && !initialLoaded" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
  </div>

  <!-- 返信フォームダイアログ -->
  <Dialog
    :visible="replyTargetId !== null"
    modal
    header="返信する"
    :style="{ width: '480px' }"
    @update:visible="(v) => { if (!v) cancelReply() }"
  >
    <Textarea
      v-model="replyContent"
      placeholder="返信を入力..."
      auto-resize
      rows="3"
      class="w-full"
      data-testid="team-timeline-comment-input"
    />
    <template #footer>
      <Button label="キャンセル" severity="secondary" text @click="cancelReply" />
      <Button
        label="返信"
        :loading="replySubmitting"
        :disabled="!replyContent.trim()"
        data-testid="team-timeline-comment-submit"
        @click="submitReply"
      />
    </template>
  </Dialog>

  <!-- リポスト確認ダイアログ -->
  <Dialog
    :visible="repostTargetId !== null"
    modal
    header="リポスト"
    :style="{ width: '360px' }"
    @update:visible="(v) => { if (!v) cancelRepost() }"
  >
    <p class="text-sm text-surface-600">この投稿をリポストしますか？</p>
    <template #footer>
      <Button label="キャンセル" severity="secondary" text @click="cancelRepost" />
      <Button
        label="リポストする"
        severity="success"
        :loading="repostSubmitting"
        @click="confirmRepost"
      />
    </template>
  </Dialog>
</template>
