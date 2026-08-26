<script setup lang="ts">
import type { SuggestedLabel } from '~/types/inbox'
import { suggestionKeyI18nKey } from '~/composables/useInboxApi'

/**
 * F04.11 wave3b — 自動ラベリング提案チップ。
 *
 * 既存の InboxLabelChip（実付与済みラベル）と視覚的に区別するため
 * 点線ボーダー・半透明背景を使用する。
 * 1タップで `apply` イベントを emit → 親（InboxList）がストアの suggestApply を呼ぶ。
 *
 * ADHD要件: 摩擦ゼロ。タップ1回で付与。確認ダイアログなし。
 */
const props = defineProps<{
  suggestion: SuggestedLabel
}>()

const emit = defineEmits<{
  apply: [suggestion: SuggestedLabel]
}>()

const { t } = useI18n()

/** 提案ラベルの表示名（i18n キーをヘルパーで解決）。 */
const labelName = computed(() => t(suggestionKeyI18nKey(props.suggestion.suggestionKey)))

/** チップの色（suggestion.color を使用）。 */
const chipColor = computed(() => props.suggestion.color)
</script>

<template>
  <button
    type="button"
    class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium transition-opacity hover:opacity-80 active:scale-95"
    :style="{
      backgroundColor: `${chipColor}11`,
      color: chipColor,
      border: `1px dashed ${chipColor}88`,
    }"
    :aria-label="t('inbox.suggestion.applyAria', { name: labelName })"
    :data-testid="`inbox-suggestion-chip-${suggestion.suggestionKey.toLowerCase()}`"
    @click.stop="emit('apply', suggestion)"
  >
    <!-- 色 dot -->
    <span
      class="inline-block h-2 w-2 rounded-full opacity-70"
      :style="{ backgroundColor: chipColor }"
    />
    <!-- 表示名 -->
    <span>{{ labelName }}</span>
    <!-- + アイコン（付与操作の示唆） -->
    <i class="pi pi-plus text-[0.55rem]" :aria-hidden="true" />
  </button>
</template>
