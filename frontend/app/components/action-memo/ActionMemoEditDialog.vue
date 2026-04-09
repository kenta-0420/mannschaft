<script setup lang="ts">
import type { ActionMemo, Mood, UpdateActionMemoPayload } from '~/types/actionMemo'

/**
 * F02.5 行動メモ編集ダイアログ（Phase 2）。
 *
 * <p>{@link ActionMemoCard} の編集ボタンから開かれる。設計書 §4.x に従い、
 * {@code content} と {@code mood}（設定 ON 時のみ）を編集できる。保存ボタンは
 * 空文字または 5,000 文字超で disabled。</p>
 *
 * <p>本ダイアログは既存メモの編集のみを扱い、新規作成は {@link ActionMemoInput} が担当する。
 * 保存後の {@code timeline_post_id} は更新しない（設計書 §5.4 重要判定ロジック:
 * 投稿済みメモの編集は旧投稿を書き換えない）。</p>
 */

const props = defineProps<{
  modelValue: boolean
  memo: ActionMemo | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [memo: ActionMemo]
}>()

const { t } = useI18n()
const store = useActionMemoStore()

const MAX_LENGTH = 5000

const content = ref<string>('')
const mood = ref<Mood | null>(null)
const submitting = ref(false)

const charCount = computed(() => content.value.length)
const tooLong = computed(() => charCount.value > MAX_LENGTH)
const isEmpty = computed(() => content.value.trim().length === 0)
const canSave = computed(() => !isEmpty.value && !tooLong.value && !submitting.value)

/**
 * props.memo が変わった / ダイアログが開かれたタイミングでフィールドを初期化。
 */
watch(
  () => [props.modelValue, props.memo] as const,
  ([open, memo]) => {
    if (open && memo) {
      content.value = memo.content
      mood.value = memo.mood
    }
    if (!open) {
      // 閉じたとき次回開くまでの間に値が見えるのを防ぐ
      submitting.value = false
    }
  },
  { immediate: true },
)

function close() {
  emit('update:modelValue', false)
}

function onBackdropClick() {
  if (!submitting.value) close()
}

async function onSave() {
  if (!canSave.value || !props.memo) return
  submitting.value = true
  try {
    const patch: UpdateActionMemoPayload = {
      content: content.value,
    }
    // mood_enabled = true の場合のみ mood を送る（OFF ユーザーの誤操作を防ぐ）
    if (store.isMoodEnabled) {
      patch.mood = mood.value
    }
    const updated = await store.updateMemo(props.memo.id, patch)
    if (updated) {
      emit('saved', updated)
      close()
    }
    // 失敗時はダイアログを閉じない（store.error に出る）
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4 py-8"
      role="dialog"
      aria-modal="true"
      data-testid="action-memo-edit-dialog"
      @click.self="onBackdropClick"
    >
      <div
        class="flex w-full max-w-md flex-col gap-3 rounded-2xl border border-surface-200 bg-surface-0 p-4 shadow-xl dark:border-surface-700 dark:bg-surface-800"
        @click.stop
      >
        <header class="flex items-center justify-between">
          <h2 class="text-base font-semibold text-surface-900 dark:text-surface-50">
            {{ t('action_memo.dialog.title') }}
          </h2>
          <button
            type="button"
            class="rounded p-1 text-surface-400 hover:bg-surface-100 dark:hover:bg-surface-700"
            :disabled="submitting"
            :aria-label="t('action_memo.dialog.cancel')"
            data-testid="action-memo-edit-dialog-close"
            @click="close"
          >
            <i class="pi pi-times text-xs" />
          </button>
        </header>

        <textarea
          v-model="content"
          rows="5"
          class="w-full resize-y rounded-lg border border-surface-200 bg-transparent p-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
          :placeholder="t('action_memo.input.placeholder')"
          data-testid="action-memo-edit-dialog-textarea"
        />

        <div v-if="store.isMoodEnabled">
          <MoodSelector v-model="mood" />
        </div>

        <div class="flex items-center justify-between text-xs">
          <span
            :class="
              tooLong
                ? 'text-rose-600'
                : 'text-surface-500 dark:text-surface-400'
            "
            data-testid="action-memo-edit-dialog-charcount"
          >
            {{ t('action_memo.input.char_count', { count: charCount }) }}
          </span>
        </div>

        <footer class="flex items-center justify-end gap-2">
          <button
            type="button"
            class="rounded-lg px-4 py-1.5 text-sm text-surface-600 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
            :disabled="submitting"
            data-testid="action-memo-edit-dialog-cancel"
            @click="close"
          >
            {{ t('action_memo.dialog.cancel') }}
          </button>
          <button
            type="button"
            class="rounded-lg bg-primary px-4 py-1.5 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="!canSave"
            data-testid="action-memo-edit-dialog-save"
            @click="onSave"
          >
            {{ t('action_memo.dialog.save') }}
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>
