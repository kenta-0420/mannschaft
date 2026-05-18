<script setup lang="ts">
/**
 * F09.17 「広告」ラベルバッジ
 *
 * 景品表示法（WG 第 5 条 3 項）対応で、広告コンテンツに必須表示するバッジ。
 * バッジ色は #FF9800 固定（設計書 §6 / §9）。
 * ARIA `role="region" aria-label="広告"` でスクリーンリーダー向けに広告領域を明示。
 *
 * 注: このコンポーネントは Phase 11-c-1（基盤）と並行作成された雛形。
 * 11-c-1 マージ時に統合される可能性がある。
 */

const { t } = useI18n()

defineProps<{
  /** small / medium / large 表示サイズ */
  size?: 'sm' | 'md'
}>()
</script>

<template>
  <span
    role="region"
    :aria-label="t('advertising.label_badge.ad')"
    class="inline-flex items-center rounded font-semibold text-white"
    :class="size === 'sm' ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-0.5 text-xs'"
    style="background-color: #ff9800"
  >
    {{ t('advertising.label_badge.ad') }}
  </span>
</template>
