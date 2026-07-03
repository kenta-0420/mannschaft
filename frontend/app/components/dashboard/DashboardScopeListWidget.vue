<script setup lang="ts" generic="T">
/**
 * F22.1 第二陣: チーム / 組織パネルの「直近3件リスト」表示用ウィジェット。
 *
 * DashboardWidgetCard を内包し、渡された items を最大 3 件見える高さで描画する
 * （残りは DashboardWidgetCard の既定挙動で枠内スクロール）。各項目のフィールドは
 * ウィジェットごとに異なるため、item スロットで呼び出し側が描画を差し込む。
 *
 * - 0 件時は emptyMessage を空状態として表示する（エラーを握り潰さない）。
 * - to 指定時のみ「すべて見る」導線をカード下部に出す（common.button.view_all）。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §3 / §4
 */
import type { RouteLocationRaw } from 'vue-router'

const props = withDefaults(
  defineProps<{
    /** カードタイトル（i18n 済みの文字列を渡す）。 */
    title: string
    /** タイトル左のアイコンクラス（例 'pi pi-calendar-plus'）。 */
    icon?: string
    /** 描画対象アイテム配列。null / undefined は空配列扱い。 */
    items?: T[] | null
    /** 0 件時に表示する空状態メッセージ（i18n 済み）。 */
    emptyMessage: string
    /** 「すべて見る」導線の遷移先。未指定なら導線を出さない。 */
    to?: string | RouteLocationRaw
  }>(),
  {
    icon: undefined,
    items: () => [],
    to: undefined,
  },
)

/** DOM 暴走防止の描画上限（20件・溢れは枠内スクロールで見える）。 */
const DISPLAY_LIMIT = 20

const list = computed<T[]>(() => (Array.isArray(props.items) ? props.items : []))
const visibleItems = computed<T[]>(() => list.value.slice(0, DISPLAY_LIMIT))
</script>

<template>
  <DashboardWidgetCard :title="title" :icon="icon">
    <div v-if="list.length > 0" class="space-y-2">
      <slot
        v-for="(item, index) in visibleItems"
        name="item"
        :item="item"
        :index="index"
      />

      <div v-if="to" class="flex justify-end pt-1">
        <NuxtLink :to="to" class="text-sm text-primary hover:underline">
          {{ $t('common.button.view_all') }}
        </NuxtLink>
      </div>
    </div>

    <DashboardEmptyState v-else :icon="icon" :message="emptyMessage" />
  </DashboardWidgetCard>
</template>
