<script setup lang="ts">
/**
 * F19.1 汎用ラベルコンポーネント。
 *
 * 「投稿者」「メンバー」「主催者」等の汎用ラベル表示。
 * i18n キー `public.author.*` を経由するため、UI への文字列直書き禁止規則に準拠。
 */
defineProps<{
  /** ラベル種別。i18n キー `public.author.{kind}` に対応 */
  kind: 'anonymous' | 'member' | 'organizer'
}>()

const { t } = useI18n()
</script>

<template>
  <span
    class="inline-flex items-center rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
  >
    {{ t(`public.author.${kind}`) }}
  </span>
</template>
