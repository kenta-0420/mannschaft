<script setup lang="ts">
/**
 * 「作成先のレイヤーが非表示です」案内（§5.4・AC-11b）。
 *
 * 表示フィルタ（レイヤーチップ）で非表示のレイヤーへ予定を作成すると、作った予定が
 * 何の説明も無く現れない（P3 違反）。これを防ぐための案内バナー。
 *
 * - 表示するだけでは表示フィルタを一切書き換えない（P2）。
 * - 「表示する」ボタンを押したときだけ、呼び出し側がそのレイヤーを表示状態にする
 *   （実際の変更は親側の責務。このコンポーネントは `show` を発火するのみ）。
 * - 設計書 §6.6.6 のとおり、月グリッドからの作成（`pages/calendar.vue`）だけでなく、
 *   将来の週ビューからのグリッド選択作成でも同じ経路として使い回す想定のため、
 *   カレンダーページ固有の状態を持たない汎用コンポーネントにしてある。
 */
defineProps<{
  /** 非表示だったレイヤーの表示名（チーム名・組織名・「個人」等）。 */
  layerLabel: string
}>()

const emit = defineEmits<{ show: [] }>()

const { t } = useI18n()
</script>

<template>
  <div
    data-testid="hidden-layer-notice"
    class="flex items-center gap-2 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200"
  >
    <i class="pi pi-eye-slash shrink-0" />
    <span class="flex-1">{{ t('schedule.calendarGrid.hiddenLayerNotice', { layer: layerLabel }) }}</span>
    <Button
      :label="t('schedule.calendarGrid.hiddenLayerShow')"
      size="small"
      text
      data-testid="hidden-layer-show-button"
      @click="emit('show')"
    />
  </div>
</template>
