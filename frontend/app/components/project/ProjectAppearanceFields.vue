<script setup lang="ts">
/**
 * プロジェクト外観フィールド共通コンポーネント。
 *
 * 絵文字プリセットグリッドとカラースウォッチをまとめて提供する。
 * 作成ダイアログ・編集ダイアログの両方で使用する。
 *
 * CardEditorCommonFields.vue のカラーラベルパターンを写経。
 */

const { t } = useI18n()

const emoji = defineModel<string | undefined>('emoji', { default: '' })
const color = defineModel<string | undefined>('color', { default: '' })

// 絵文字プリセット（約30個）
const EMOJI_PRESETS = [
  '📚', '📝', '🎯', '🚀', '💡', '🎨', '🏆', '📊',
  '🔧', '🎓', '💼', '🌟', '🔥', '⭐', '📌', '✅',
  '🎉', '🏗️', '🧩', '📈', '⚽', '🎮', '🛠️', '📅',
  '🌱', '🐾', '🎵', '💰', '🔬', '🗂️',
] as const

// カラープリセット
interface ColorOption {
  hex: string
  label: string
}

const COLOR_OPTIONS: ColorOption[] = [
  { hex: '#3B82F6', label: 'Blue' },
  { hex: '#EF4444', label: 'Red' },
  { hex: '#10B981', label: 'Green' },
  { hex: '#F59E0B', label: 'Amber' },
  { hex: '#8B5CF6', label: 'Violet' },
  { hex: '#EC4899', label: 'Pink' },
  { hex: '#06B6D4', label: 'Cyan' },
  { hex: '#14B8A6', label: 'Teal' },
  { hex: '#F97316', label: 'Orange' },
  { hex: '#6B7280', label: 'Gray' },
]

// プリセット外の絵文字が現在値として来た場合の判定
const emojiVal = computed(() => emoji.value ?? '')
const colorVal = computed(() => color.value ?? '')

const isCustomEmoji = computed(
  () => emojiVal.value !== '' && !(EMOJI_PRESETS as readonly string[]).includes(emojiVal.value),
)

// プリセット外のカラーが来た場合の判定
const isCustomColor = computed(
  () => colorVal.value !== '' && !COLOR_OPTIONS.some((o) => o.hex === colorVal.value),
)

function selectEmoji(value: string) {
  emoji.value = value === emojiVal.value ? '' : value
}
</script>

<template>
  <!-- 絵文字選択 -->
  <div class="flex flex-col gap-1">
    <span class="text-sm font-medium">{{ t('project.appearance.emoji_label') }}</span>

    <!-- プリセット外の既存値チップ -->
    <div v-if="isCustomEmoji" class="mb-1 flex items-center gap-2">
      <span class="text-xs text-surface-500">{{ t('project.appearance.current') }}:</span>
      <button
        type="button"
        class="flex h-8 w-8 items-center justify-center rounded border-2 border-primary text-lg"
        :aria-label="emojiVal"
        @click="emoji = ''"
      >{{ emojiVal }}</button>
      <span class="text-xs text-surface-400">({{ t('project.appearance.custom_emoji') }})</span>
    </div>

    <div
      role="radiogroup"
      :aria-label="t('project.appearance.emoji_label')"
      class="flex flex-wrap gap-1"
    >
      <!-- なしボタン -->
      <button
        type="button"
        role="radio"
        :aria-checked="emojiVal === ''"
        :aria-label="t('project.appearance.none')"
        data-testid="project-emoji-option-none"
        class="flex h-8 w-8 items-center justify-center rounded border-2 text-xs transition-all"
        :class="
          emojiVal === ''
            ? 'border-primary bg-primary/10 scale-110'
            : 'border-surface-300 dark:border-surface-600 hover:scale-105'
        "
        @click="emoji = ''"
      >
        <i class="pi pi-times text-[10px]" aria-hidden="true" />
      </button>

      <!-- プリセット絵文字ボタン -->
      <button
        v-for="preset in EMOJI_PRESETS"
        :key="preset"
        type="button"
        role="radio"
        :aria-checked="emojiVal === preset"
        :aria-label="preset"
        :data-testid="`project-emoji-option-${preset}`"
        class="flex h-8 w-8 items-center justify-center rounded border-2 text-lg transition-all"
        :class="
          emojiVal === preset
            ? 'border-primary bg-primary/10 scale-110'
            : 'border-surface-300 dark:border-surface-600 hover:scale-105'
        "
        @click="selectEmoji(preset)"
      >{{ preset }}</button>
    </div>
  </div>

  <!-- カラー選択 -->
  <div class="flex flex-col gap-1">
    <span class="text-sm font-medium">{{ t('project.appearance.color_label') }}</span>

    <div
      role="radiogroup"
      :aria-label="t('project.appearance.color_label')"
      class="flex flex-wrap gap-2"
    >
      <button
        v-for="opt in COLOR_OPTIONS"
        :key="opt.hex"
        type="button"
        role="radio"
        :aria-checked="colorVal === opt.hex"
        :aria-label="opt.label"
        :data-testid="`project-color-option-${opt.hex}`"
        class="flex h-8 w-8 items-center justify-center rounded-full border-2 transition-all"
        :style="{ backgroundColor: opt.hex }"
        :class="
          colorVal === opt.hex
            ? 'border-primary scale-110'
            : 'border-surface-300 dark:border-surface-600 hover:scale-105'
        "
        @click="color = opt.hex"
      >
        <i
          v-if="colorVal === opt.hex"
          class="pi pi-check text-[10px] text-white"
          aria-hidden="true"
        />
      </button>
    </div>

    <!-- カスタムカラー入力（プリセット外の指定対応） -->
    <div class="mt-1 flex items-center gap-2">
      <label class="text-xs text-surface-500" for="project-color-custom">
        {{ t('project.appearance.custom_color') }}
      </label>
      <input
        id="project-color-custom"
        type="color"
        :value="colorVal || '#3B82F6'"
        class="h-7 w-10 cursor-pointer rounded border border-surface-300 bg-transparent p-0.5"
        data-testid="project-color-custom-input"
        @input="(e) => { color = (e.target as HTMLInputElement).value }"
      >
      <span v-if="isCustomColor" class="text-xs text-surface-400">
        {{ colorVal }}
      </span>
    </div>
  </div>
</template>
