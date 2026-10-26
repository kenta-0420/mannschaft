<script setup lang="ts">
import type { PublicPostComment } from '~/types/public'

/**
 * F19.1 Phase 6-B: 公開投稿コメント 1 件表示コンポーネント。
 *
 * - 投稿者名・コメント本文・投稿日時を表示
 * - 自分のコメント or ADMIN の場合は削除ボタンを表示
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6-B
 */
const props = defineProps<{
  comment: PublicPostComment
  /** 現在ログイン中のユーザー ID（未ログイン時は undefined） */
  currentUserId?: number
  /** ADMIN 権限フラグ */
  isAdmin?: boolean
}>()

const emit = defineEmits<{
  delete: [commentId: string]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

const canDelete = computed(
  () =>
    props.currentUserId !== undefined &&
    (props.currentUserId === props.comment.authorId || props.isAdmin),
)

const formattedDate = computed(() => formatDateTime(props.comment.createdAt))

function handleDelete() {
  if (!confirm(t('public.comments.deleteConfirm'))) return
  emit('delete', props.comment.commentId)
}
</script>

<template>
  <div
    class="flex items-start gap-3 border-b border-surface-100 py-4 last:border-0 dark:border-surface-800"
    data-testid="comment-item"
  >
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-2">
        <span class="text-sm font-semibold text-surface-900 dark:text-surface-50">
          {{ comment.authorDisplayName }}
        </span>
        <time
          :datetime="comment.createdAt"
          class="text-xs text-surface-400 dark:text-surface-500"
        >
          {{ formattedDate }}
        </time>
      </div>
      <p class="mt-1 whitespace-pre-wrap break-words text-sm text-surface-700 dark:text-surface-300">
        {{ comment.content }}
      </p>
    </div>
    <Button
      v-if="canDelete"
      :label="t('public.comments.delete')"
      severity="danger"
      text
      size="small"
      data-testid="comment-delete"
      @click="handleDelete"
    />
  </div>
</template>
