<script setup lang="ts">
/**
 * F09.17 「広告」ラベルバッジ。
 *
 * <p>景品表示法に準拠し、広告由来の表示物（お知らせ・メール・プッシュ・バナー）には
 * 必ず本コンポーネントを併記する。配色 `#FF9800`（橙色）とロケール別表記
 * （広告 / Ad / 广告 / 광고 / Anzeige / Anuncio）で識別性を担保する。</p>
 *
 * <p>意味的にはボタンではなく単純ラベルのため `role="region"` でラベル化する。
 * クリック可能要素にしてはいけない。</p>
 *
 * 使用例:
 * <pre>
 *   &lt;AdLabelBadge /&gt;
 *   &lt;AdLabelBadge size="sm" /&gt;
 * </pre>
 */

import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  /** 表示サイズ。`sm` は一覧の見出し横、`md` はカード上部などで使う。 */
  size?: 'sm' | 'md'
}

const props = withDefaults(defineProps<Props>(), {
  size: 'md',
})

const { t } = useI18n()

const label = computed(() => t('advertising.ad_label'))

const sizeClass = computed(() =>
  props.size === 'sm'
    ? 'text-[10px] px-1.5 py-0.5'
    : 'text-xs px-2 py-0.5',
)
</script>

<template>
  <span
    role="region"
    :aria-label="label"
    class="inline-flex items-center rounded font-bold text-white select-none"
    :class="sizeClass"
    style="background-color: #FF9800"
    data-testid="ad-label-badge"
  >
    {{ label }}
  </span>
</template>
