<script setup lang="ts">
/**
 * F09.19.4 ダッシュボード掲載面（DASHBOARD_TILE）1 枠目のウィジェット外皮（設計 §8.2）。
 *
 * <p>データ取得は親パネル（DashboardPersonalPanel / ScopeDashboard）が
 * {@code GET /spotlight/content?placement=DASHBOARD_TILE&count=2} で 1 回だけ行い、
 * items[0] を本ウィジェットに渡す。候補が無ければ親が本ウィジェットを描画しない。</p>
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
  <div class="spotlight-primary" data-testid="spotlight-primary">
    <SpotlightSlot :item="item" placement="DASHBOARD_TILE" />
  </div>
</template>
