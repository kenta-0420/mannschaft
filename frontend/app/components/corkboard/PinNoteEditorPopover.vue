<script setup lang="ts">
/**
 * F09.8 件3' (V9.098): ピン止め時の付箋メモ・色選択ポップオーバー。
 *
 * - 📌 ボタン押下で開き、textarea + カラーピッカーで付箋メモを書く。
 * - 「ピン止めする」確定で `confirm(userNote, noteColor)` を emit。
 * - 「キャンセル」で何もせず閉じる。
 *
 * 親 (`pages/corkboard/[id].vue` / `WidgetMyCorkboard.vue` 等) からは PrimeVue `<Popover>`
 * の中身として `<PinNoteEditorPopover>` を埋めて使う想定だが、本実装ではコンポーネント
 * 自身で開閉状態 (`v-model:visible`) を持つよう dialog 風 modal に近い形で実装する。
 *
 * デフォルト色 = カードの colorLabel（親が `defaultColor` で渡す）。
 *
 * A11y:
 *  - role="dialog" / aria-modal="true" / aria-labelledby
 *  - 開いた瞬間に textarea へフォーカス
 *  - Escape キーでキャンセル
 *  - カラーピッカーは role="radiogroup"
 */
import type { CorkboardColor } from '~/types/corkboard'

interface Props {
  /** 開閉状態（v-model:visible） */
  visible: boolean
  /** デフォルト色（カードの colorLabel）。null/undefined のときは `WHITE` を採用 */
  defaultColor?: CorkboardColor | string | null
  /** 既存の付箋メモ本文（再ピン時にプリセットする用）。空文字 / null で空欄スタート */
  initialUserNote?: string | null
  /**
   * テスト ID 用のサフィックス（複数カードで使い分けるため）。
   * 例: cardId=42 を渡すと `data-testid="pin-note-popover-42"` 等になる。
   */
  testidSuffix?: string | number
}

const props = withDefaults(defineProps<Props>(), {
  defaultColor: 'WHITE',
  initialUserNote: '',
  testidSuffix: '',
})

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 「ピン止めする」確定。userNote は空文字 OK、noteColor はカラーピッカー選択値。 */
  (e: 'confirm', userNote: string, noteColor: CorkboardColor): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()

// ----- フォーム状態 -----

const userNote = ref<string>('')
const noteColor = ref<CorkboardColor>('WHITE')
const textareaEl = ref<HTMLTextAreaElement | null>(null)

/** カラーピッカー選択肢（既存 CardEditorModal.vue の colorOptions と同パターン） */
const colorOptions = computed<Array<{ value: CorkboardColor; label: string; swatch: string }>>(() => [
  { value: 'WHITE', label: t('corkboard.pinNote.colors.white'), swatch: 'bg-surface-200' },
  { value: 'YELLOW', label: t('corkboard.pinNote.colors.yellow'), swatch: 'bg-yellow-400' },
  { value: 'BLUE', label: t('corkboard.pinNote.colors.blue'), swatch: 'bg-blue-400' },
  { value: 'GREEN', label: t('corkboard.pinNote.colors.green'), swatch: 'bg-green-400' },
  { value: 'RED', label: t('corkboard.pinNote.colors.red'), swatch: 'bg-red-400' },
  { value: 'PURPLE', label: t('corkboard.pinNote.colors.purple'), swatch: 'bg-purple-400' },
  { value: 'GRAY', label: t('corkboard.pinNote.colors.gray'), swatch: 'bg-gray-400' },
])

/** デフォルト色を CorkboardColor 列挙に正規化する（不正値は WHITE フォールバック） */
function normalizeColor(c: CorkboardColor | string | null | undefined): CorkboardColor {
  if (!c) return 'WHITE'
  const upper = String(c).toUpperCase()
  const allowed: CorkboardColor[] = ['WHITE', 'YELLOW', 'RED', 'BLUE', 'GREEN', 'PURPLE', 'GRAY']
  return (allowed as string[]).includes(upper) ? (upper as CorkboardColor) : 'WHITE'
}

/** 付箋プレビュー用の背景色クラス（textarea 直接背景）。 */
const previewBgClass = computed<string>(() => {
  const map: Record<CorkboardColor, string> = {
    WHITE: 'bg-surface-100 dark:bg-surface-800',
    YELLOW: 'bg-yellow-100 dark:bg-yellow-900/40',
    BLUE: 'bg-blue-100 dark:bg-blue-900/40',
    GREEN: 'bg-green-100 dark:bg-green-900/40',
    RED: 'bg-red-100 dark:bg-red-900/40',
    PURPLE: 'bg-purple-100 dark:bg-purple-900/40',
    GRAY: 'bg-gray-100 dark:bg-gray-800',
  }
  return map[noteColor.value] ?? map.WHITE
})

// ----- visible (v-model 連携) + 初期化 -----

const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

/** モーダルが開いた瞬間にフォームを初期化し、textarea にフォーカス。 */
watch(
  () => props.visible,
  async (v) => {
    if (v) {
      userNote.value = props.initialUserNote ?? ''
      noteColor.value = normalizeColor(props.defaultColor)
      // DOM 描画完了後にフォーカスを当てる
      await nextTick()
      textareaEl.value?.focus()
    }
  },
)

// ----- アクション -----

function onConfirm() {
  emit('confirm', userNote.value, noteColor.value)
  dialogVisible.value = false
}

function onCancel() {
  emit('cancel')
  dialogVisible.value = false
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    onCancel()
  }
}

const dialogTestId = computed(() =>
  props.testidSuffix !== '' ? `pin-note-popover-${props.testidSuffix}` : 'pin-note-popover',
)
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    modal
    :header="t('corkboard.pinNote.title')"
    :style="{ width: '420px' }"
    :data-testid="dialogTestId"
    role="dialog"
    aria-modal="true"
    @keydown="onKeydown"
  >
    <div class="flex flex-col gap-3">
      <!-- 付箋メモ textarea（背景色は選択色プレビュー） -->
      <label
        :for="`pin-note-textarea-${String(testidSuffix)}`"
        class="sr-only"
      >
        {{ t('corkboard.pinNote.placeholder') }}
      </label>
      <textarea
        :id="`pin-note-textarea-${String(testidSuffix)}`"
        ref="textareaEl"
        v-model="userNote"
        rows="4"
        :placeholder="t('corkboard.pinNote.placeholder')"
        class="w-full rounded-md border border-surface-300 px-3 py-2 text-sm shadow-inner transition-colors focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-600"
        :class="previewBgClass"
        :data-testid="`pin-note-textarea-${String(testidSuffix)}`"
        :data-note-color="noteColor"
        :aria-label="t('corkboard.pinNote.placeholder')"
      />

      <!-- カラーピッカー -->
      <div class="flex flex-col gap-1">
        <span class="text-xs font-medium text-surface-700 dark:text-surface-200">
          {{ t('corkboard.pinNote.colorLabel') }}
        </span>
        <div
          role="radiogroup"
          :aria-label="t('corkboard.pinNote.colorLabel')"
          class="flex flex-wrap gap-2"
          :data-testid="`pin-note-color-picker-${String(testidSuffix)}`"
        >
          <button
            v-for="opt in colorOptions"
            :key="opt.value"
            type="button"
            role="radio"
            :aria-checked="noteColor === opt.value"
            :aria-label="opt.label"
            :data-testid="`pin-note-color-${opt.value}`"
            class="flex h-8 w-8 items-center justify-center rounded-full border-2 transition-all"
            :class="[
              opt.swatch,
              noteColor === opt.value
                ? 'border-primary scale-110'
                : 'border-surface-300 dark:border-surface-600 hover:scale-105',
            ]"
            @click="noteColor = opt.value"
          >
            <i
              v-if="noteColor === opt.value"
              class="pi pi-check text-[10px] text-surface-900 dark:text-surface-50"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('corkboard.pinNote.cancel')"
        severity="secondary"
        text
        :data-testid="`pin-note-cancel-${String(testidSuffix)}`"
        @click="onCancel"
      />
      <Button
        :label="t('corkboard.pinNote.confirm')"
        icon="pi pi-bookmark"
        :data-testid="`pin-note-confirm-${String(testidSuffix)}`"
        @click="onConfirm"
      />
    </template>
  </Dialog>
</template>
