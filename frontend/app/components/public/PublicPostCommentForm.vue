<script setup lang="ts">
/**
 * F19.1 Phase 6-B: コメント投稿フォーム。
 *
 * - テキストエリア（max 1000 文字）
 * - 送信ボタン（送信中はローディング状態）
 * - 送信完了後に親へ refresh イベントを emit
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6-B
 */
const props = defineProps<{
  postId: number | string
}>()

const emit = defineEmits<{
  submitted: []
}>()

const { t } = useI18n()
const { postComment } = usePublicApi()

const content = ref('')
const submitting = ref(false)
const MAX_LENGTH = 1000

// SSR 配信済み HTML に @submit.prevent が未結合の窓で送信ボタンを押されると、
// ブラウザ標準のフォーム送信が走って入力が失われるため、ハイドレーション完了まで送信を封じる。
const hydrated = useHydrated()

async function handleSubmit() {
  const trimmed = content.value.trim()
  if (!trimmed || submitting.value) return

  submitting.value = true
  try {
    await postComment(props.postId, trimmed)
    content.value = ''
    emit('submitted')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form class="flex flex-col gap-2" @submit.prevent="handleSubmit">
    <Textarea
      v-model="content"
      :placeholder="t('public.comments.placeholder')"
      :maxlength="MAX_LENGTH"
      rows="3"
      class="w-full resize-y"
      data-testid="comment-input"
      :disabled="submitting"
    />
    <div class="flex items-center justify-between">
      <span class="text-xs text-surface-400">
        {{ content.length }} / {{ MAX_LENGTH }}
      </span>
      <Button
        type="submit"
        :label="t('public.comments.submit')"
        :loading="submitting"
        :disabled="!hydrated || !content.trim() || submitting"
        data-testid="comment-submit"
      />
    </div>
  </form>
</template>
