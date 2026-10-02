<script setup lang="ts">
import type { UserPointCardDetail } from '~/types/pointCard'

/**
 * カード詳細ページの基本情報 (`section_meta`) と任意項目 (`section_optional`) セクション。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * カード ID コピー UX もここに含む。クリップボード書き込みの実処理は親に残してあり、
 * 本コンポーネントは `copy-card-id` イベントを emit するだけ。</p>
 */

interface Props {
  /** カード詳細 DTO。 */
  card: UserPointCardDetail
  /** 親で整形した最終使用日時表示。 */
  lastUsedDisplay: string
  /**
   * 自店発行カード（SELF_ISSUED_STAMP / SELF_ISSUED_BALANCE）のとき true。
   * カード ID 行（店員が押印時に客のカードを特定する識別子・UC-9）の表示判定に使う。
   * EXTERNAL カードでは用途が無く紛らわしいため非表示にする。
   * 親 `[id].vue` の `isSelfIssued` computed と同一の条件（「店舗で提示」ボタンと揃える）。
   */
  isSelfIssued: boolean
}

defineProps<Props>()
const emit = defineEmits<(e: 'copy-card-id') => void>()

const { t } = useI18n()
</script>

<template>
  <section class="card-detail__section">
    <h2 class="card-detail__section-title">{{ t('wallet.detail.section_meta') }}</h2>
    <dl class="card-detail__dl">
      <div class="card-detail__dl-row">
        <dt>{{ t('wallet.add.display_name') }}</dt>
        <dd>{{ card.displayName }}</dd>
      </div>
      <div v-if="card.providerDisplayName" class="card-detail__dl-row">
        <dt>Provider</dt>
        <dd>{{ card.providerDisplayName }}</dd>
      </div>
      <div v-if="card.last4" class="card-detail__dl-row">
        <dt>Last 4</dt>
        <dd>{{ card.last4 }}</dd>
      </div>
      <div class="card-detail__dl-row">
        <dt>Format</dt>
        <dd>{{ card.barcodeFormat }}</dd>
      </div>
      <!-- カード ID 行は自店発行カードのみ表示（店員が押印時に客カードを特定する識別子・UC-9）。
           EXTERNAL カードでは用途が無く紛らわしいため非表示。「店舗で提示」ボタンと同一条件。 -->
      <div v-if="isSelfIssued" class="card-detail__dl-row">
        <dt>{{ t('wallet.card_id_label') }}</dt>
        <dd class="card-detail__id-cell">
          <code class="card-detail__id-code bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-200">{{ card.id }}</code>
          <Button
            :label="t('wallet.copy_card_id')"
            :aria-label="t('wallet.copy_card_id')"
            severity="secondary"
            size="small"
            @click="emit('copy-card-id')"
          />
        </dd>
      </div>
      <div class="card-detail__dl-row">
        <dt>{{ t('wallet.detail.last_used_at') }}</dt>
        <dd>{{ lastUsedDisplay }}</dd>
      </div>
    </dl>
  </section>

  <section v-if="card.nickname || card.memo" class="card-detail__section">
    <h2 class="card-detail__section-title">{{ t('wallet.detail.section_optional') }}</h2>
    <dl class="card-detail__dl">
      <div v-if="card.nickname" class="card-detail__dl-row">
        <dt>{{ t('wallet.detail.nickname') }}</dt>
        <dd>{{ card.nickname }}</dd>
      </div>
      <div v-if="card.memo" class="card-detail__dl-row">
        <dt>{{ t('wallet.detail.memo') }}</dt>
        <dd class="card-detail__memo">{{ card.memo }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.card-detail__section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.card-detail__section-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0;
}
.card-detail__dl {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
}
.card-detail__dl-row {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--p-surface-200, #e5e7eb);
}
.card-detail__dl-row dt {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.card-detail__dl-row dd {
  font-size: 0.9375rem;
  margin: 0;
  text-align: right;
  word-break: break-all;
}
.card-detail__memo {
  white-space: pre-wrap;
  text-align: right;
}
.card-detail__id-cell {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.card-detail__id-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8125rem;
  /* 背景・文字色は Tailwind の dark: クラス（bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-200）で追従 */
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  word-break: break-all;
}
</style>
