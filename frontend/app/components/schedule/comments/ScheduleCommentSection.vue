<script setup lang="ts">
/**
 * F03.16 予定コメントスレッド — セクション本体。
 *
 * 設計書: docs/features/F03.16_schedule_comment_thread.md §4・§5・§9
 *
 * - 一覧（トップレベル + 最新3件同梱の返信）とスレッド状態（meta）を並行取得（§4.1 の但し書き）
 * - `comments_enabled=false` / 予定中止時は `meta.canPost` に従い投稿 UI を出さない
 * - 深さ上限1（返信ボタンは depth=0 のみ）
 * - トゥームストーン（削除済みだが生存返信ありの親）はそのまま一覧に残す（BE 応答をそのまま描画）
 * - スレッド開閉は `canManageSettings`（親から渡される予定編集権限の代理指標。§2.1.1 の
 *   厳密な3者判定は BE が最終防御として再評価するため、FE 側はヒントとして扱う）
 *
 * 全 8 エンドポイントが認証必須。未ログイン向けの分岐は持たない（§2.1 再訂正）。
 */
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import ScheduleCommentForm from './ScheduleCommentForm.vue'
import ScheduleCommentItem from './ScheduleCommentItem.vue'
import type { ScheduleCommentResponse, ScheduleCommentThreadMeta } from '~/types/scheduleComment'

const props = defineProps<{
  scheduleId: number
  /** スレッド開閉トグルの表示可否ヒント（実際の可否は BE が §2.1.1 の3者判定で再評価する）。 */
  canManageSettings?: boolean
  /** 通知リンク（?commentId=）由来のハイライト対象コメント ID（設計書 §6.4）。 */
  highlightCommentId?: string | null
}>()

const emit = defineEmits<{
  /** ハイライト処理（発見/未発見いずれも）が完了した合図。呼び出し元は commentId クエリを除去してよい。 */
  highlighted: []
}>()

const { t } = useI18n()
const notification = useNotification()
const {
  listComments,
  getMeta,
  listReplies,
  createComment,
  updateComment,
  deleteComment,
  updateSettings,
} = useScheduleComments()

const PAGE_SIZE = 20

const loading = ref(true)
const comments = ref<ScheduleCommentResponse[]>([])
const page = ref(0)
const totalPages = ref(0)
const total = ref(0)

const meta = ref<ScheduleCommentThreadMeta | null>(null)
const metaLoading = ref(true)

const submitting = ref(false)
const editingId = ref<string | null>(null)
const editSubmitting = ref(false)
const replyTarget = ref<ScheduleCommentResponse | null>(null)
const settingsSubmitting = ref(false)

const expandedReplies = ref<Record<string, ScheduleCommentResponse[]>>({})
// 全件取得済みの返信ページ（0始まり）。「もっと見る」で続きを取得するために保持する（是正5）。
const expandedReplyPage = ref<Record<string, number>>({})
// まだ未取得のページが残っているか（true の間だけ「もっと見る」を出す）。
const expandedReplyHasMore = ref<Record<string, boolean>>({})
const expandingId = ref<string | null>(null)
const loadingMoreId = ref<string | null>(null)

const highlightedCommentId = ref<string | null>(null)
let highlightFadeTimer: ReturnType<typeof setTimeout> | undefined

const canPostReasonText = computed(() => {
  switch (meta.value?.canPostReason) {
    case 'CANCELLED':
      return t('schedule.comment.cancelled')
    case 'CLOSED':
      return t('schedule.comment.closed')
    case 'ROLE':
      return t('schedule.comment.error.forbidden')
    default:
      return null
  }
})

async function loadMeta() {
  metaLoading.value = true
  try {
    const res = await getMeta(props.scheduleId)
    meta.value = res.data
  } catch {
    meta.value = null
  } finally {
    metaLoading.value = false
  }
}

async function loadComments(targetPage = 0) {
  loading.value = true
  try {
    const res = await listComments(props.scheduleId, { page: targetPage, size: PAGE_SIZE })
    comments.value = res.data
    page.value = res.meta.page
    totalPages.value = res.meta.totalPages
    total.value = res.meta.total
    expandedReplies.value = {}
    expandedReplyPage.value = {}
    expandedReplyHasMore.value = {}
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    loading.value = false
  }
}

function repliesFor(comment: ScheduleCommentResponse): ScheduleCommentResponse[] {
  return expandedReplies.value[comment.id] ?? comment.replies ?? []
}

function hiddenReplyCount(comment: ScheduleCommentResponse): number {
  const shown = repliesFor(comment).length
  return Math.max(0, comment.replyCount - shown)
}

function moreRepliesAvailable(comment: ScheduleCommentResponse): boolean {
  return expandedReplyHasMore.value[comment.id] === true
}

/** 「他 N 件の返信を表示」— 先頭ページ（最大50件）を取得する。 */
async function expandReplies(comment: ScheduleCommentResponse) {
  expandingId.value = comment.id
  try {
    const res = await listReplies(props.scheduleId, comment.id, { page: 0, size: 50 })
    expandedReplies.value = { ...expandedReplies.value, [comment.id]: res.data }
    expandedReplyPage.value = { ...expandedReplyPage.value, [comment.id]: res.meta.page }
    expandedReplyHasMore.value = {
      ...expandedReplyHasMore.value,
      [comment.id]: res.meta.page + 1 < res.meta.totalPages,
    }
  } catch {
    // 是正5: 失敗を握りつぶさず通知し、状態（既存の展開結果）は変更しない。
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    expandingId.value = null
  }
}

/**
 * 是正5【P2】: 返信が51件以上ある場合に「もっと見る」で続きのページを取得し、
 * 既存の展開結果へ追記する（`page=0&size=50` 固定で置き換えるだけの旧実装は
 * 51件目以降に永久に到達できなかった）。
 */
async function loadMoreReplies(comment: ScheduleCommentResponse) {
  const nextPage = (expandedReplyPage.value[comment.id] ?? 0) + 1
  loadingMoreId.value = comment.id
  try {
    const res = await listReplies(props.scheduleId, comment.id, { page: nextPage, size: 50 })
    const existing = expandedReplies.value[comment.id] ?? []
    expandedReplies.value = { ...expandedReplies.value, [comment.id]: [...existing, ...res.data] }
    expandedReplyPage.value = { ...expandedReplyPage.value, [comment.id]: res.meta.page }
    expandedReplyHasMore.value = {
      ...expandedReplyHasMore.value,
      [comment.id]: res.meta.page + 1 < res.meta.totalPages,
    }
  } catch {
    // 失敗を握りつぶさない。既に取得済みの返信はそのまま保持し（状態を壊さない）、再試行可能にする。
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    loadingMoreId.value = null
  }
}

async function submitTop(body: string, mentionedUserIds: number[]) {
  submitting.value = true
  try {
    await createComment(props.scheduleId, { body, parentId: null, mentionedUserIds })
    notification.success(t('schedule.comment.postSuccess'))
    await Promise.all([loadComments(page.value), loadMeta()])
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    submitting.value = false
  }
}

async function submitReply(body: string, mentionedUserIds: number[]) {
  if (!replyTarget.value) return
  submitting.value = true
  try {
    await createComment(props.scheduleId, {
      body,
      parentId: replyTarget.value.id,
      mentionedUserIds,
    })
    notification.success(t('schedule.comment.postSuccess'))
    replyTarget.value = null
    await loadComments(page.value)
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    submitting.value = false
  }
}

function startReply(comment: ScheduleCommentResponse) {
  editingId.value = null
  replyTarget.value = comment
}

function startEdit(comment: ScheduleCommentResponse) {
  replyTarget.value = null
  editingId.value = comment.id
}

async function saveEdit(commentId: string, body: string) {
  editSubmitting.value = true
  try {
    await updateComment(props.scheduleId, commentId, { body })
    notification.success(t('schedule.comment.updateSuccess'))
    editingId.value = null
    await loadComments(page.value)
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    editSubmitting.value = false
  }
}

async function remove(commentId: string) {
  try {
    await deleteComment(props.scheduleId, commentId)
    notification.success(t('schedule.comment.deleteSuccess'))
    await loadComments(page.value)
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  }
}

async function toggleSettings() {
  if (!meta.value) return
  settingsSubmitting.value = true
  try {
    await updateSettings(props.scheduleId, !meta.value.commentsEnabled)
    notification.success(t('schedule.comment.settings.updateSuccess'))
    await loadMeta()
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    settingsSubmitting.value = false
  }
}

/**
 * 是正1【P1】: 通知リンク（?commentId=）で開いた際、該当コメントまでスクロールし一時ハイライトする
 * （設計書 §6.4 の4項目）。トップレベル一覧・埋め込み済み返信（先頭3件）の範囲で探す。
 * 見つからない場合は「黙って無視」せず、トーストで知らせる（症状を隠さない）。
 * 発見・未発見のいずれでも、処理完了を親へ通知し commentId クエリの除去を促す。
 */
async function highlightTargetComment() {
  const targetId = props.highlightCommentId
  if (!targetId) return

  let found = comments.value.some(c => c.id === targetId)
  if (!found) {
    found = comments.value.some(c => repliesFor(c).some(r => r.id === targetId))
  }

  if (found) {
    highlightedCommentId.value = targetId
    await nextTick()
    const el = document.querySelector(`[data-testid="schedule-comment-item-${targetId}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    if (highlightFadeTimer) clearTimeout(highlightFadeTimer)
    highlightFadeTimer = setTimeout(() => {
      highlightedCommentId.value = null
    }, 4000)
  } else {
    notification.error(t('schedule.comment.error.notFound'))
  }
  emit('highlighted')
}

onMounted(async () => {
  await Promise.all([loadComments(0), loadMeta()])
  await highlightTargetComment()
})

onBeforeUnmount(() => {
  if (highlightFadeTimer) clearTimeout(highlightFadeTimer)
})
</script>

<template>
  <section class="space-y-3" data-testid="schedule-comment-section">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold">
        {{ t('schedule.comment.title') }}
        <span v-if="total > 0" class="ml-1 text-xs font-normal text-surface-400">
          {{ t('schedule.comment.count', { count: total }) }}
        </span>
      </h3>
      <Button
        v-if="canManageSettings && meta"
        :label="meta.commentsEnabled
          ? t('schedule.comment.settings.disable')
          : t('schedule.comment.settings.enable')"
        text
        size="small"
        :loading="settingsSubmitting"
        data-testid="schedule-comment-settings-toggle"
        @click="toggleSettings"
      />
    </div>

    <!-- 一覧 -->
    <div v-if="loading" class="space-y-3">
      <Skeleton height="3rem" />
      <Skeleton height="3rem" />
    </div>
    <div v-else-if="comments.length === 0" class="py-3 text-center text-sm text-surface-400">
      {{ t('schedule.comment.empty') }}
    </div>
    <div v-else class="space-y-4">
      <div v-for="c in comments" :key="c.id" class="space-y-2">
        <div :class="{ 'schedule-comment-highlight': highlightedCommentId === c.id }">
          <ScheduleCommentItem
            :comment="c"
            :can-reply="!!meta?.canPost"
            :editing="editingId === c.id"
            :edit-submitting="editSubmitting"
            @reply="startReply"
            @start-edit="startEdit"
            @cancel-edit="editingId = null"
            @save-edit="saveEdit"
            @delete="remove"
          />
        </div>

        <!-- 返信フォーム（このコメントに返信中のみ表示） -->
        <div v-if="replyTarget?.id === c.id" class="ml-11">
          <ScheduleCommentForm
            :schedule-id="scheduleId"
            :parent-id="c.id"
            :reply-to-name="c.author?.displayName ?? t('schedule.comment.deletedAuthor')"
            :submitting="submitting"
            autofocus
            @submit="submitReply"
            @cancel="replyTarget = null"
          />
        </div>

        <!-- 返信（最大3件同梱・残りは「他N件」で全件取得） -->
        <div v-if="repliesFor(c).length > 0 || hiddenReplyCount(c) > 0" class="ml-11 space-y-2 border-l border-surface-200 pl-3 dark:border-surface-700">
          <div
            v-for="r in repliesFor(c)"
            :key="r.id"
            :class="{ 'schedule-comment-highlight': highlightedCommentId === r.id }"
          >
            <ScheduleCommentItem
              :comment="r"
              :can-reply="false"
              :editing="editingId === r.id"
              :edit-submitting="editSubmitting"
              @start-edit="startEdit"
              @cancel-edit="editingId = null"
              @save-edit="saveEdit"
              @delete="remove"
            />
          </div>
          <!-- 是正5: 51件目以降も「もっと見る」で続きのページを追記取得する（page=0&size=50 固定の置換をやめる）。 -->
          <Button
            v-if="hiddenReplyCount(c) > 0 && !moreRepliesAvailable(c) && expandedReplyPage[c.id] === undefined"
            :label="t('schedule.comment.showReplies', { count: hiddenReplyCount(c) })"
            text
            size="small"
            :loading="expandingId === c.id"
            @click="expandReplies(c)"
          />
          <Button
            v-else-if="hiddenReplyCount(c) > 0 && moreRepliesAvailable(c)"
            :label="t('schedule.comment.showReplies', { count: hiddenReplyCount(c) })"
            text
            size="small"
            :loading="loadingMoreId === c.id"
            @click="loadMoreReplies(c)"
          />
        </div>
      </div>

      <!-- ページング -->
      <div v-if="totalPages > 1" class="flex items-center justify-between pt-1">
        <Button
          icon="pi pi-angle-left"
          text
          size="small"
          :disabled="page === 0"
          @click="loadComments(page - 1)"
        />
        <span class="text-xs text-surface-400">{{ page + 1 }} / {{ totalPages }}</span>
        <Button
          icon="pi pi-angle-right"
          text
          size="small"
          :disabled="page >= totalPages - 1"
          @click="loadComments(page + 1)"
        />
      </div>
    </div>

    <!-- 投稿フォーム / 締切理由 -->
    <div v-if="!metaLoading">
      <ScheduleCommentForm
        v-if="meta?.canPost"
        :schedule-id="scheduleId"
        :submitting="submitting"
        @submit="submitTop"
      />
      <p
        v-else-if="canPostReasonText"
        class="text-xs text-surface-400"
        data-testid="schedule-comment-cannot-post"
      >
        {{ canPostReasonText }}
      </p>
    </div>
  </section>
</template>

<style scoped>
/* 是正1: 通知リンクから開いた対象コメントの一時ハイライト（設計書 §6.4 の2項目目）。数秒でフェードする。 */
.schedule-comment-highlight {
  border-radius: 0.5rem;
  animation: schedule-comment-highlight-fade 4s ease-out;
}

@keyframes schedule-comment-highlight-fade {
  0% {
    background-color: rgba(99, 102, 241, 0.18);
  }
  100% {
    background-color: transparent;
  }
}
</style>
