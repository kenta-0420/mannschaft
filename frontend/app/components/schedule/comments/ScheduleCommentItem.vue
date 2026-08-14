<script setup lang="ts">
/**
 * F03.16 予定コメント — コメント1件（トップレベル or 返信）。
 *
 * - トゥームストーン（isDeleted かつ replyCount>0）は「このコメントは削除されました」の枠のみ表示
 * - 編集済み（isEdited）は「（編集済み）」を添える
 * - 返信ボタンは depth=0 のみ（深さ上限1・§3.3.1）
 * - canEdit / canDelete は BE が返す値をそのまま使う（FE で再実装しない・§4.2 の二層防御方針）
 */
import Button from 'primevue/button'
import type { ScheduleCommentResponse } from '~/types/scheduleComment'

const props = defineProps<{
  comment: ScheduleCommentResponse
  canReply: boolean
  editing: boolean
  editSubmitting: boolean
}>()

const emit = defineEmits<{
  reply: [comment: ScheduleCommentResponse]
  'start-edit': [comment: ScheduleCommentResponse]
  'cancel-edit': []
  'save-edit': [commentId: string, body: string]
  delete: [commentId: string]
}>()

const { t } = useI18n()
const { formatRelative } = useRelativeTime()

const editBody = ref(props.comment.body ?? '')
watch(
  () => props.editing,
  (v) => {
    if (v) editBody.value = props.comment.body ?? ''
  },
)

function saveEdit() {
  const trimmed = editBody.value.trim()
  if (!trimmed) return
  emit('save-edit', props.comment.id, trimmed)
}

function confirmDelete() {
  if (!confirm(t('schedule.comment.deleteConfirm'))) return
  emit('delete', props.comment.id)
}
</script>

<template>
  <div class="flex gap-3" :data-testid="`schedule-comment-item-${comment.id}`">
    <template v-if="comment.isDeleted">
      <div class="flex-1 rounded border border-dashed border-surface-300 p-2 text-sm italic text-surface-400 dark:border-surface-600">
        {{ t('schedule.comment.deleted') }}
      </div>
    </template>
    <template v-else>
      <Avatar
        :image="comment.author?.avatarUrl ?? undefined"
        :label="comment.author?.avatarUrl ? undefined : (comment.author?.displayName?.charAt(0) ?? '?')"
        shape="circle"
        size="normal"
      />
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <span class="text-sm font-medium">
            {{ comment.author?.displayName ?? t('schedule.comment.deletedAuthor') }}
          </span>
          <span class="text-xs text-surface-400">{{ formatRelative(comment.createdAt) }}</span>
          <span v-if="comment.isEdited" class="text-xs text-surface-400">
            {{ t('schedule.comment.edited') }}
          </span>
        </div>

        <div v-if="editing" class="mt-1">
          <Textarea v-model="editBody" rows="2" class="w-full" auto-resize />
          <div class="mt-1 flex gap-1">
            <Button
              :label="t('schedule.comment.save')"
              size="small"
              :loading="editSubmitting"
              :disabled="!editBody.trim()"
              @click="saveEdit"
            />
            <Button
              :label="t('schedule.comment.cancel')"
              size="small"
              text
              @click="emit('cancel-edit')"
            />
          </div>
        </div>
        <template v-else>
          <p class="mt-1 whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">
            {{ comment.body }}
          </p>
          <div class="mt-1 flex gap-1">
            <Button
              v-if="canReply && comment.depth === 0"
              :label="t('schedule.comment.reply')"
              text
              size="small"
              @click="emit('reply', comment)"
            />
            <Button
              v-if="comment.canEdit"
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              :aria-label="t('schedule.comment.edit')"
              @click="emit('start-edit', comment)"
            />
            <Button
              v-if="comment.canDelete"
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('schedule.comment.delete')"
              @click="confirmDelete"
            />
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
