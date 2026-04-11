<script setup lang="ts">
import type { Mood } from '~/types/actionMemo'

/**
 * F02.5 行動メモ入力フォーム。
 *
 * <p>設計書 §4.x / §5.1 に従い、以下の要件を満たす:</p>
 * <ul>
 *   <li>Enter で送信、Shift+Enter で改行</li>
 *   <li>空文字 / 5,000 文字超で送信ボタン disabled</li>
 *   <li>{@code mood_enabled = true} の場合のみ MoodSelector を表示</li>
 *   <li>下書き自動保存（debounce 1秒）→ 送信成功でクリア</li>
 *   <li>送信失敗時は入力欄をクリアしない（書きかけを失わせない）</li>
 * </ul>
 */

const store = useActionMemoStore()
const auth = useAuthStore()
const { t } = useI18n()

const MAX_LENGTH = 5000
const DRAFT_DEBOUNCE_MS = 1000

const content = ref<string>('')
const mood = ref<Mood | null>(null)
const selectedTagIds = ref<number[]>([])
const submitting = ref(false)
const draftSaveTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const draftSavedFlash = ref(false)

const userId = computed<number | string>(() => auth.user?.id ?? 'anon')

const charCount = computed(() => content.value.length)
const tooLong = computed(() => charCount.value > MAX_LENGTH)
const isEmpty = computed(() => content.value.trim().length === 0)
const canSubmit = computed(() => !isEmpty.value && !tooLong.value && !submitting.value)

// === 下書きの復元 ===
onMounted(() => {
  const draft = store.loadDraft(userId.value)
  if (draft) {
    content.value = draft
  }
})

// === 下書きの自動保存 (debounce 1秒) ===
watch(content, (next) => {
  if (draftSaveTimer.value) clearTimeout(draftSaveTimer.value)
  draftSaveTimer.value = setTimeout(() => {
    store.saveDraft(userId.value, next)
    if (next.length > 0) {
      draftSavedFlash.value = true
      setTimeout(() => (draftSavedFlash.value = false), 1500)
    }
    draftSaveTimer.value = null
  }, DRAFT_DEBOUNCE_MS)
})

onBeforeUnmount(() => {
  if (draftSaveTimer.value) clearTimeout(draftSaveTimer.value)
})

// === 送信処理 ===
async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const created = await store.createMemo({
      content: content.value,
      mood: store.isMoodEnabled ? mood.value : null,
      tagIds: selectedTagIds.value.length > 0 ? selectedTagIds.value : undefined,
    })
    if (created) {
      // 成功 → 入力欄クリア + mood + タグ リセット
      content.value = ''
      mood.value = null
      selectedTagIds.value = []
      store.clearDraft(userId.value)
    }
    // 失敗時は入力欄を保持（下書きとしてそのまま）
  } finally {
    submitting.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  // Shift+Enter は通常の改行（テキストエリアのデフォルト動作に任せる）
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}
</script>

<template>
  <form
    class="flex flex-col gap-2 rounded-2xl border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
    data-testid="action-memo-input"
    @submit.prevent="submit"
  >
    <textarea
      v-model="content"
      data-testid="action-memo-input-textarea"
      :placeholder="t('action_memo.input.placeholder')"
      rows="3"
      class="w-full resize-y rounded-lg border border-surface-200 bg-transparent p-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
      @keydown="onKeydown"
    />

    <div v-if="store.isMoodEnabled" class="px-1">
      <MoodSelector v-model="mood" />
    </div>

    <div class="px-1">
      <TagPicker v-model="selectedTagIds" />
    </div>

    <div class="flex flex-wrap items-center justify-between gap-2 px-1 text-xs">
      <div class="flex items-center gap-3">
        <span
          :class="
            tooLong ? 'text-rose-600' : 'text-surface-500 dark:text-surface-400'
          "
          data-testid="action-memo-input-charcount"
        >
          {{ t('action_memo.input.char_count', { count: charCount }) }}
        </span>
        <span class="hidden text-surface-400 sm:inline">
          {{ t('action_memo.input.shift_enter_hint') }}
        </span>
        <span
          v-if="draftSavedFlash"
          class="text-emerald-500"
          data-testid="action-memo-input-draftsaved"
        >
          {{ t('action_memo.input.draft_saved') }}
        </span>
      </div>
      <button
        type="submit"
        data-testid="action-memo-input-submit"
        :disabled="!canSubmit"
        class="rounded-lg bg-primary px-4 py-1.5 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
      >
        {{ t('action_memo.input.submit') }}
      </button>
    </div>
  </form>
</template>
