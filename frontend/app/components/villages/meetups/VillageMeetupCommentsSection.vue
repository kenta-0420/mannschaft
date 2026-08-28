<script setup lang="ts">
/**
 * 寄合詳細 — コメントセクション（F17.2 Wave1 ②寄合後半戦 §4.4）。
 *
 * - CONFIRMED のみ投稿フォームを表示（PLANNING/CANCELLED は書込み不可・§4.5）
 * - 一覧は作成日昇順（親が渡した順序をそのまま描画）
 * - 削除ボタンは投稿者本人＋村長/長老のみ表示（BE も同条件でガード）
 * - 表示は村ニックネーム（`displayName`）のみ。実名は出さない（G4）
 *
 * ロジックは持たない（API 呼び出しは親が担う）。
 */
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import type { VillageMeetupCommentResponse } from '~/types/village'

const props = defineProps<{
  comments: VillageMeetupCommentResponse[]
  currentUserId: number | null
  /** 村長/長老か（削除の可否判定に使う） */
  isAdmin: boolean
  /** コメントを投稿できるか（CONFIRMED のみ） */
  canWrite: boolean
  loading: boolean
  submitting: boolean
}>()

const emit = defineEmits<{
  submit: [body: string]
  remove: [commentId: string]
}>()

const { t } = useI18n()
const draft = ref('')

function canDelete(comment: VillageMeetupCommentResponse): boolean {
  return props.isAdmin || (props.currentUserId !== null && comment.authorUserId === props.currentUserId)
}

function submit() {
  const body = draft.value.trim()
  if (!body) return
  emit('submit', body)
  draft.value = ''
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <h3 class="font-semibold">
      {{ t('village.meetup.comment.title') }}
    </h3>

    <div v-if="loading" class="text-center py-3 text-surface-500">
      <i class="pi pi-spin pi-spinner" />
    </div>
    <div v-else-if="comments.length === 0" class="text-xs text-surface-500">
      {{ t('village.meetup.comment.empty') }}
    </div>
    <div v-else class="flex flex-col gap-2 max-h-64 overflow-y-auto">
      <div
        v-for="c in comments"
        :key="c.id"
        class="rounded border border-surface-200 p-2 text-sm dark:border-surface-700"
      >
        <div class="flex items-center justify-between gap-2">
          <span class="font-medium text-xs text-surface-600 dark:text-surface-300">
            {{ c.displayName ?? t('village.meetup.attendance.unknownMember') }}
          </span>
          <Button
            v-if="canDelete(c)"
            icon="pi pi-trash"
            severity="danger"
            text
            size="small"
            :aria-label="t('village.meetup.comment.delete')"
            @click="emit('remove', c.id)"
          />
        </div>
        <p class="whitespace-pre-wrap mt-1">
          {{ c.body }}
        </p>
      </div>
    </div>

    <div v-if="canWrite" class="flex flex-col gap-2 mt-1">
      <Textarea
        v-model="draft"
        rows="2"
        class="w-full"
        :placeholder="t('village.meetup.comment.placeholder')"
      />
      <Button
        :label="t('village.meetup.comment.submit')"
        icon="pi pi-send"
        size="small"
        severity="primary"
        class="self-end"
        :disabled="!draft.trim() || submitting"
        :loading="submitting"
        @click="submit"
      />
    </div>
  </div>
</template>
