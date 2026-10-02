<script setup lang="ts">
/**
 * F09.19.4 ダッシュボード掲載面（DASHBOARD_TILE）2 枠目のウィジェット外皮（設計 §8.2）。
 *
 * <p>親パネルが取得した items[1] を渡す。items が 1 件以下のときは親が本ウィジェットを
 * DOM ごと描画しない（候補なし枠は非表示・スケルトンも確保しない）。</p>
 *
 * <p>本枠は {@code v-for} ループ外の固定描画（order-last）であり、KEYS / linkTo には
 * 登録しない（結線パリティ規約の対象外）。</p>
 */
import type { SpotlightItem } from '~/composables/useSpotlightApi'

defineProps<{
  item: SpotlightItem
}>()
</script>

<template>
  <div class="spotlight-secondary" data-testid="spotlight-secondary">
    <SpotlightSlot :item="item" placement="DASHBOARD_TILE" />
  </div>
</template>
