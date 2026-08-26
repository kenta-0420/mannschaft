<script setup lang="ts">
import type { PointCardGroupListItem } from '~/types/pointCard'

/**
 * F18 ウォレットホーム — グループタイル。
 *
 * <p>提示モードへの主要な入口となるため、タッチターゲットを大きめに（最低 88px 高）取る。
 * 設計書 §8.4 に基づき「2 / 4」のように複数カード切替の連続提示への動線を強調する。</p>
 *
 * <p>UX 再構成（案A）: タイル本体タップで提示モード（{@code /wallet/groups/{id}/show}）を開始し、
 * 右端の「✎ 編集」アイコンボタンで編集ページ（{@code /wallet/groups/{id}}）へ遷移する。
 * ネストした {@code <a>} は不正なため、外側は非リンクのコンテナにし、本体（NuxtLink）と
 * 編集アイコン（NuxtLink）をそれぞれ独立させる。</p>
 *
 * <p>空グループ（{@code cardCount === 0}）は提示できないため、本体タップも編集ページへ回す。</p>
 */

const props = defineProps<{
  group: PointCardGroupListItem
}>()

const { t } = useI18n()

const displayEmoji = computed(() => props.group.emoji ?? t('wallet.group.no_emoji'))

const cardCountLabel = computed(() =>
  t('wallet.group.card_count', { count: props.group.cardCount }),
)

/** 空グループは提示できないので本体タップも編集へ回す。 */
const isEmpty = computed(() => props.group.cardCount === 0)

const primaryTarget = computed(() =>
  isEmpty.value
    ? `/wallet/groups/${props.group.id}`
    : `/wallet/groups/${props.group.id}/show`,
)

const editTarget = computed(() => `/wallet/groups/${props.group.id}`)

const primaryAriaLabel = computed(() =>
  isEmpty.value
    ? t('wallet.group.edit_group', { name: props.group.name })
    : t('wallet.group.start_presentation_for', { name: props.group.name }),
)

/** ツールチップ: 空グループは編集、それ以外はバーコード表示を示す。 */
const primaryTooltip = computed(() =>
  isEmpty.value
    ? t('wallet.group.edit_tooltip')
    : t('wallet.group.show_barcode_tooltip'),
)
</script>

<template>
  <div class="group-tile">
    <NuxtLink
      v-tooltip.top="primaryTooltip"
      :to="primaryTarget"
      class="group-tile__main hover:bg-surface-50 focus-visible:bg-surface-50 dark:hover:bg-surface-800 dark:focus-visible:bg-surface-800"
      :aria-label="primaryAriaLabel"
    >
      <div class="group-tile__emoji" aria-hidden="true">{{ displayEmoji }}</div>
      <div class="group-tile__body">
        <div class="group-tile__name">{{ group.name }}</div>
        <div class="group-tile__count">{{ cardCountLabel }}</div>
      </div>
      <div v-if="!isEmpty" class="group-tile__chevron" aria-hidden="true">▶</div>
    </NuxtLink>
    <NuxtLink
      :to="editTarget"
      class="group-tile__edit hover:bg-surface-50 focus-visible:bg-surface-50 dark:hover:bg-surface-800 dark:focus-visible:bg-surface-800"
      :aria-label="t('wallet.group.edit')"
    >
      <span aria-hidden="true">✎</span>
    </NuxtLink>
  </div>
</template>

<style scoped>
.group-tile {
  display: flex;
  align-items: stretch;
  gap: 0.25rem;
  border-radius: 0.75rem;
  background: var(--p-content-background, #fff);
  border: 1px solid var(--p-surface-200, #e5e7eb);
  min-height: 88px;
  overflow: hidden;
}
.group-tile__main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  text-decoration: none;
  color: inherit;
  transition: background 0.15s ease;
}
/* ホバー/フォーカス背景は Tailwind の dark: バリアントで白化を防ぐ（template class 参照）。 */
.group-tile__main:hover,
.group-tile__main:focus-visible {
  outline: none;
}
.group-tile__main:focus-visible {
  box-shadow: inset 0 0 0 2px var(--p-primary-color, #3b82f6);
}
.group-tile__emoji {
  font-size: 2.5rem;
  line-height: 1;
  flex-shrink: 0;
}
.group-tile__body {
  flex: 1;
  min-width: 0;
}
.group-tile__name {
  font-weight: 600;
  font-size: 1.0625rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-tile__count {
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
  margin-top: 0.25rem;
}
.group-tile__chevron {
  flex-shrink: 0;
  font-size: 1.125rem;
  color: var(--p-primary-color, #3b82f6);
}
.group-tile__edit {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  border-left: 1px solid var(--p-surface-200, #e5e7eb);
  color: var(--p-text-muted-color, #6b7280);
  text-decoration: none;
  font-size: 1.25rem;
  transition: background 0.15s ease;
}
/* ホバー/フォーカス背景は Tailwind の dark: バリアントで白化を防ぐ（template class 参照）。 */
.group-tile__edit:hover,
.group-tile__edit:focus-visible {
  color: var(--p-text-color, #111827);
  outline: none;
}
.group-tile__edit:focus-visible {
  box-shadow: inset 0 0 0 2px var(--p-primary-color, #3b82f6);
}
</style>
