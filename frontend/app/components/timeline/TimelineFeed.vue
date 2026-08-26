<script setup lang="ts">
import type { TimelinePostResponse, TimelineScopeType, TimelineMute, TimelineMutedType } from '~/types/timeline'

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

// 投稿カード本体クリックは各カード内の返信アコーディオン開閉に統一済み。
// 旧 clickPost（詳細遷移）の中継はどのページからも購読されなくなったため撤去した。

const {
  getFeed,
  getMyTimeline,
  addBookmark,
  removeBookmark,
  pinPost,
  deletePost,
  repost,
} = useTimelineApi()
const { showSuccess, showError } = useNotification()
const { showUndoToast } = useUndoToast()
const { t } = useI18n()

// --- ミュート（個人集約フィードのみ・CMP-058） ---
const { mutes, muteCount, loading: mutesLoading, loadMutes, mute: addMuteEntry, unmute } = useTimelineMutes()
const mutedListVisible = ref(false)
/**
 * ミュートした対象の表示名の控え。BE の `MuteResponse` は名前を返さないため、
 * ミュート実行時に投稿カードから受け取った名前をここに残して一覧に出す。
 */
const mutedNames = ref<Record<string, string>>({})

function mutedNameKey(mutedType: TimelineMutedType, mutedId: number): string {
  return `${mutedType}-${mutedId}`
}

function resolveMutedName(m: TimelineMute): string | null {
  return mutedNames.value[mutedNameKey(m.mutedType, m.mutedId)] ?? null
}

/** ミュート0件のときはチップ自体を出さない（段階開示）。 */
const showMutedChip = computed(() => !!props.myFeed && muteCount.value > 0)

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

/** 返信アコーディオンで返信が追加されたら返信数を +1（対象 post の shared ref を更新）。 */
function onReplyAdded(postId: number) {
  const post = [...pinnedPosts.value, ...posts.value].find((p) => p.id === postId)
  if (post?.stats) post.stats.replyCount += 1
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

// --- ミュート操作 ---

/** 指定スコープの投稿を一覧から即時除去する（楽観更新）。 */
function removePostsOfScope(mutedType: TimelineMutedType, mutedId: number) {
  const matches = (p: TimelinePostResponse) =>
    p.scope?.scopeType === mutedType && Number(p.scope.scopeId) === mutedId
  posts.value = posts.value.filter((p) => !matches(p))
  pinnedPosts.value = pinnedPosts.value.filter((p) => !matches(p))
}

/**
 * 投稿カードの「非表示にする」。確認ダイアログは出さず即実行し（摩擦ゼロ）、
 * 「元に戻す」付きトーストで誤タップから復旧できるようにする（ADHD 配慮）。
 * API が失敗した場合は楽観更新を巻き戻し、エラーは useTimelineMutes 側で必ずユーザーに見せる。
 */
async function onMute(payload: { postId: number, mutedType: TimelineMutedType, mutedId: number, name: string }) {
  const { mutedType, mutedId, name } = payload
  const removed = [...pinnedPosts.value, ...posts.value].filter(
    (p) => p.scope?.scopeType === mutedType && Number(p.scope.scopeId) === mutedId,
  )
  removePostsOfScope(mutedType, mutedId)
  if (name) mutedNames.value[mutedNameKey(mutedType, mutedId)] = name

  const ok = await addMuteEntry(mutedType, mutedId)
  if (!ok) {
    // 失敗（200件上限 TIMELINE_017 等）。エラーは表示済みなので、消した投稿を戻して状態を偽らない。
    if (removed.length > 0) refresh()
    return
  }

  showUndoToast({
    severity: 'success',
    summary: t('timeline.mute.muted', { name: name || t(`timeline.mute.targetType.${mutedType}`) }),
    undoLabel: t('timeline.mute.undo'),
    onUndo: async () => {
      const undone = await unmute(mutedType, mutedId)
      if (undone) refresh()
    },
  })
}

/** ミュート一覧ダイアログからの解除。解除後はフィードを取り直して投稿を復帰させる。 */
async function onUnmute(m: TimelineMute) {
  const ok = await unmute(m.mutedType, m.mutedId)
  if (!ok) return
  const removedKey = mutedNameKey(m.mutedType, m.mutedId)
  mutedNames.value = Object.fromEntries(
    Object.entries(mutedNames.value).filter(([key]) => key !== removedKey),
  )
  showSuccess(t('timeline.mute.unmuted'))
  refresh()
}

function openMutedList() {
  mutedListVisible.value = true
}

function refresh() {
  loadFeed()
}

onMounted(() => {
  loadFeed()
  // 個人集約フィードのみミュート一覧を取得する（チップ表示・件数の根拠）。
  if (props.myFeed) void loadMutes()
})

defineExpose({ refresh })
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- 非表示中チップ（個人集約フィードのみ・0件のときは出さない） -->
    <div v-if="showMutedChip" class="flex justify-end">
      <button
        type="button"
        class="inline-flex min-h-11 items-center gap-1 rounded-full bg-surface-100 px-3 text-xs font-medium text-surface-600 hover:bg-surface-200 dark:bg-surface-700 dark:text-surface-200"
        data-testid="timeline-muted-chip"
        @click="openMutedList"
      >
        <i class="pi pi-eye-slash text-[10px]" />
        <span>{{ t('timeline.mute.chip', { count: muteCount }) }}</span>
      </button>
    </div>

    <!-- ピン留め投稿 -->
    <TimelinePostCard
      v-for="post in pinnedPosts"
      :key="`pin-${post.id}`"
      :post="post"
      :can-pin="canPin"
      :can-delete-others="canDeleteOthers"
      :can-mute="myFeed"
      @mitayo-toggled="onMitayoToggled"
      @reply-added="onReplyAdded"
      @bookmark="onBookmark"
      @pin="onPin"
      @delete="onDelete"
      @repost="onRepost"
      @mute="onMute"
    />

    <!-- 通常投稿 -->
    <TimelinePostCard
      v-for="post in displayPosts"
      :key="post.id"
      :post="post"
      :can-pin="canPin"
      :can-delete-others="canDeleteOthers"
      :can-mute="myFeed"
      @mitayo-toggled="onMitayoToggled"
      @reply-added="onReplyAdded"
      @bookmark="onBookmark"
      @pin="onPin"
      @delete="onDelete"
      @repost="onRepost"
      @mute="onMute"
    />

    <!-- 空状態 -->
    <div
      v-if="initialLoaded && posts.length === 0 && pinnedPosts.length === 0"
      class="py-12 text-center"
    >
      <i class="pi pi-comments mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400 dark:text-surface-300">まだ投稿がありません</p>
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

  <!-- 非表示中（ミュート）一覧ダイアログ -->
  <TimelineMutedListDialog
    v-if="myFeed"
    v-model:visible="mutedListVisible"
    :mutes="mutes"
    :loading="mutesLoading"
    :name-resolver="resolveMutedName"
    @unmute="onUnmute"
  />

  <!-- リポスト確認ダイアログ -->
  <Dialog
    :visible="repostTargetId !== null"
    modal
    header="リポスト"
    :style="{ width: '360px' }"
    @update:visible="(v) => { if (!v) cancelRepost() }"
  >
    <p class="text-sm text-surface-600 dark:text-surface-300">この投稿をリポストしますか？</p>
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
