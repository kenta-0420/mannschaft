<script setup lang="ts">
import type { PointCardGroupListItem } from '~/types/pointCard'

/**
 * F18 ウォレットホーム — グループタイル。
 *
 * <p>提示モードへの主要な入口となるため、タッチターゲットを大きめに（最低 88px 高）取る。
 * 設計書 §8.4 に基づき「2 / 4」のように複数カード切替の連続提示への動線を強調する。</p>
 *
 * <p>クリックで `/wallet/groups/{id}` へ遷移（4B フェーズで実装される編集ページ。
 * 提示モードは 5 フェーズで `/wallet/groups/{id}/show` を実装）。</p>
 */

const props = defineProps<{
  group: PointCardGroupListItem
}>()

const { t } = useI18n()

const displayEmoji = computed(() => props.group.emoji ?? t('wallet.group.no_emoji'))

const cardCountLabel = computed(() =>
  t('wallet.group.card_count', { count: props.group.cardCount }),
)
</script>

<template>
  <NuxtLink :to="`/wallet/groups/${group.id}`" class="group-tile">
    <div class="group-tile__emoji" aria-hidden="true">{{ displayEmoji }}</div>
    <div class="group-tile__body">
      <div class="group-tile__name">{{ group.name }}</div>
      <div class="group-tile__count">{{ cardCountLabel }}</div>
    </div>
    <div class="group-tile__chevron" aria-hidden="true">›</div>
  </NuxtLink>
</template>

<style scoped>
.group-tile {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  border-radius: 0.75rem;
  background: var(--p-content-background, #fff);
  border: 1px solid var(--p-surface-200, #e5e7eb);
  text-decoration: none;
  color: inherit;
  transition: background 0.15s ease;
  min-height: 88px;
}
.group-tile:hover,
.group-tile:focus-visible {
  background: var(--p-surface-50, #f9fafb);
  outline: none;
}
.group-tile:focus-visible {
  box-shadow: 0 0 0 2px var(--p-primary-color, #3b82f6);
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
  font-size: 1.5rem;
  color: var(--p-text-muted-color, #9ca3af);
}
</style>
