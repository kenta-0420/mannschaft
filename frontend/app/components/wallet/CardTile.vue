<script setup lang="ts">
import type { UserPointCardListItem } from '~/types/pointCard'
import { getContrastColor, getInitialAvatarColor, getInitialChar } from '~/utils/pointCardColor'

/**
 * F18 ウォレットホーム — カード一覧用タイル。
 *
 * <p>設計書 §8.2 / §8.5 に基づき、プロバイダー（providerLogoUrl/Code）が判明している場合は
 * ロゴ + brandColor 背景を、未マッチの場合は displayName の頭文字 + 自動カラーを描画する。
 * 文字色は背景に対する WCAG AA コントラスト判定で白/黒を自動切替する。</p>
 *
 * <p>last4 は `●●●● 1234` 形式で表示し、肩越し閲覧防止しつつ識別性は保つ。</p>
 *
 * <p>クリックで `/wallet/cards/{id}` へ遷移（4B フェーズで実装される詳細ページ）。</p>
 */

const props = defineProps<{
  card: UserPointCardListItem
}>()

const { t } = useI18n()

const hasProvider = computed(() => !!props.card.providerCode || !!props.card.providerLogoUrl)

/** 背景色: providerBrandColor があればそれ、無ければ displayName からの決定論的 HSL */
const backgroundColor = computed(() => {
  if (props.card.providerBrandColor) return props.card.providerBrandColor
  return getInitialAvatarColor(props.card.displayName)
})

/** 文字色: 背景色に対する WCAG AA 黒/白判定。HSL の場合は明度から計算 */
const foregroundColor = computed(() => {
  if (props.card.providerBrandColor) {
    return getContrastColor(props.card.providerBrandColor)
  }
  // HSL(_, 60%, 50%) は中明度のため、視認性確保のため白固定
  return '#FFFFFF'
})

const initialChar = computed(() => getInitialChar(props.card.displayName))

const last4Display = computed(() => {
  if (!props.card.last4) return ''
  return t('wallet.card.last4_format', { last4: props.card.last4 })
})

/**
 * 残高表示（SELF_ISSUED_BALANCE のみ）。MVP は JPY 固定。
 * バックエンドが返さない場合は null として扱い、空文字を返す。
 */
const balanceDisplay = computed(() => {
  if (props.card.providerType !== 'SELF_ISSUED_BALANCE') return ''
  if (props.card.balance === null || props.card.balance === undefined) return ''
  return new Intl.NumberFormat('ja-JP', {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(props.card.balance)
})

/**
 * スタンプ数表示（SELF_ISSUED_STAMP のみ）。上限値は無いため累計のみ。
 */
const stampCountDisplay = computed(() => {
  if (props.card.providerType !== 'SELF_ISSUED_STAMP') return ''
  if (props.card.stampCount === null || props.card.stampCount === undefined) return ''
  return t('wallet.stamp_display.count', { count: props.card.stampCount })
})

const ariaLabel = computed(() => {
  const provider = props.card.providerDisplayName
  const fav = props.card.favorite ? `, ${t('wallet.card.favorite')}` : ''
  const tail4 = props.card.last4 ? `, ${last4Display.value}` : ''
  const bal = balanceDisplay.value ? `, ${t('wallet.balance_display.label')} ${balanceDisplay.value}` : ''
  const stamp = stampCountDisplay.value ? `, ${stampCountDisplay.value}` : ''
  return `${provider ?? props.card.displayName}${tail4}${bal}${stamp}${fav}`
})
</script>

<template>
  <NuxtLink
    :to="`/wallet/cards/${card.id}`"
    class="card-tile hover:bg-surface-50 dark:hover:bg-surface-800 focus-visible:bg-surface-50 dark:focus-visible:bg-surface-800"
    :aria-label="ariaLabel"
  >
    <div class="card-tile__visual" :style="{ backgroundColor }">
      <img
        v-if="hasProvider && card.providerLogoUrl"
        :src="card.providerLogoUrl"
        :alt="card.providerDisplayName ?? ''"
        class="card-tile__logo"
      >
      <span v-else class="card-tile__initial" :style="{ color: foregroundColor }">
        {{ initialChar }}
      </span>
    </div>
    <div class="card-tile__body">
      <div class="card-tile__main">
        <span class="card-tile__name">{{ card.displayName }}</span>
        <span
          v-if="card.providerDisplayName && card.providerDisplayName !== card.displayName"
          class="card-tile__provider"
        >{{ card.providerDisplayName }}</span>
      </div>
      <div class="card-tile__meta">
        <span v-if="card.last4" class="card-tile__last4">{{ last4Display }}</span>
        <span
          v-if="balanceDisplay"
          class="card-tile__balance"
          :aria-label="t('wallet.balance_display.label')"
        >{{ balanceDisplay }}</span>
        <span
          v-else-if="stampCountDisplay"
          class="card-tile__stamp"
          :aria-label="t('wallet.stamp_display.label')"
        >{{ stampCountDisplay }}</span>
        <span
          v-if="card.favorite"
          class="card-tile__favorite"
          :aria-label="t('wallet.card.favorite')"
        >★</span>
      </div>
    </div>
  </NuxtLink>
</template>

<style scoped>
.card-tile {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  border-radius: 0.75rem;
  background: var(--p-content-background, #fff);
  border: 1px solid var(--p-surface-200, #e5e7eb);
  text-decoration: none;
  color: inherit;
  transition: background 0.15s ease;
  min-height: 72px;
}
/* ホバー/フォーカス背景は Tailwind の dark: バリアントで dark 追従させる（template class 参照）。
   GroupTile と同方式。scoped CSS からは background を持たず、box-shadow のみ担当。 */
.card-tile:hover,
.card-tile:focus-visible {
  outline: none;
}
.card-tile:focus-visible {
  box-shadow: 0 0 0 2px var(--p-primary-color, #3b82f6);
}
.card-tile__visual {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: 0.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.card-tile__logo {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #fff;
}
.card-tile__initial {
  font-size: 1.75rem;
  font-weight: 700;
  line-height: 1;
}
.card-tile__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.card-tile__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.card-tile__name {
  font-weight: 600;
  font-size: 1rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-tile__provider {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-tile__meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
}
.card-tile__last4 {
  font-family: var(--font-mono, ui-monospace, monospace);
  letter-spacing: 0.05em;
  color: var(--p-text-color, #111827);
}
.card-tile__balance,
.card-tile__stamp {
  font-weight: 600;
  color: var(--p-primary-color, #3b82f6);
  margin-left: auto;
}
.card-tile__favorite {
  color: #f59e0b;
  font-size: 1rem;
}
</style>
