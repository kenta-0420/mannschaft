<script setup lang="ts">
/**
 * i18n テキストを表示する共通コンポーネント。
 * translate="no" を自動付与するため、開発者が個別に指定する必要がない。
 *
 * 使用例:
 *   <I18nText i18nKey="button.save" />                    → <span translate="no">保存</span>
 *   <I18nText i18nKey="dialog.confirm_delete" as="p" />  → <p translate="no">...</p>
 */
defineOptions({ inheritAttrs: false })

withDefaults(
  defineProps<{
    /** i18n メッセージキー（例: "button.save", "common:button.save"） */
    i18nKey: string
    /** レンダリングする HTML 要素（デフォルト: span） */
    as?: string
  }>(),
  { as: 'span' },
)
</script>

<template>
  <!-- translate="no" を自動付与し Chrome の二重翻訳を防ぐ -->
  <component :is="as" translate="no" v-bind="$attrs">
    {{ $t(i18nKey) }}
  </component>
</template>
