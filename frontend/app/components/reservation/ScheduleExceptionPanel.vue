<script setup lang="ts">
/**
 * 例外日カレンダー（F03.4.5 §3.3）ADMIN限定。
 *
 * 【W2-1-FE 第一隊（本コンポーネント）の実装範囲】
 * 管理タブ⑤の骨格（置き場所）のみ。月表示ミニカレンダー・「この日を休業にする」「臨時営業する」
 * ダイアログ・`generate-single-day` API 結線・全日休業との衝突警告（§3.3.1/§3.3.2/S-7/S-8/S-12）は
 * すべて **第二隊（W2-1 後段）が本ファイルへ実装する**（設計書 §3.3・写経元 ReservationUnavailabilityManager）。
 *
 * 症状を隠さない原則（CLAUDE.md 障害対応の原則）に従い、未実装のまま空白のセクションを置かず
 * 「準備中」であることを正直に表示し、当面の代替導線（詳細設定「個別の枠を手動管理」＝ SlotManager）を案内する。
 */
defineProps<{
  teamId: string
}>()

const { t } = useI18n()
</script>

<template>
  <div>
    <h3 class="mb-1 text-lg font-semibold">{{ t('reservation.exception_day.title') }}</h3>
    <DashboardEmptyState
      icon="pi pi-calendar-times"
      :message="t('reservation.exception_day.coming_soon')"
      :sub-message="t('reservation.exception_day.coming_soon_hint')"
    />
  </div>
</template>
