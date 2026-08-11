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
const expandingId = ref<string | null>(null)

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

async function expandReplies(comment: ScheduleCommentResponse) {
  expandingId.value = comment.id
  try {
    const res = await listReplies(props.scheduleId, comment.id, { size: 50 })
    expandedReplies.value = { ...expandedReplies.value, [comment.id]: res.data }
  } catch {
    notification.error(t('schedule.comment.error.generic'))
  } finally {
    expandingId.value = null
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

onMounted(() => {
  loadComments(0)
  loadMeta()
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
          <ScheduleCommentItem
            v-for="r in repliesFor(c)"
            :key="r.id"
            :comment="r"
            :can-reply="false"
            :editing="editingId === r.id"
            :edit-submitting="editSubmitting"
            @start-edit="startEdit"
            @cancel-edit="editingId = null"
            @save-edit="saveEdit"
            @delete="remove"
          />
          <Button
            v-if="hiddenReplyCount(c) > 0"
            :label="t('schedule.comment.showReplies', { count: hiddenReplyCount(c) })"
            text
            size="small"
            :loading="expandingId === c.id"
            @click="expandReplies(c)"
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
