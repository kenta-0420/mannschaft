<script setup lang="ts">
/**
 * カード詳細ページの編集フォームセクション。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * 振る舞い・CSS クラス・i18n キー・バリデーション挙動は元と完全同一。
 *
 * <p>編集中のドラフトは `defineModel` 経由で親と双方向バインドする。
 * 親側は `ref<DraftState>` を `v-model:draft` で渡せばよく、
 * 子から各フィールド更新するたびに親側 ref が直接書き換わる（元実装の
 * `reactive` 直接ミューテートと等価な挙動）。</p>
 */

interface DraftState {
  displayName: string
  nickname: string
  memo: string
  favorite: boolean
  displayOrder: number
}

interface Props {
  /** 保存処理中フラグ。ボタン disabled に使う。 */
  saving: boolean
  /** 保存失敗時のエラーメッセージ。null の場合は表示しない。 */
  saveError: string | null
}

defineProps<Props>()
const emit = defineEmits<(e: 'cancel' | 'save') => void>()

/** 編集ドラフト本体。`v-model:draft` で親 ref と双方向バインドする。 */
const draft = defineModel<DraftState>('draft', { required: true })

const { t } = useI18n()
</script>

<template>
  <section class="card-detail__section">
    <p class="card-detail__hint bg-surface-50 dark:bg-surface-800 text-surface-600 dark:text-surface-300">{{ t('wallet.detail.barcode_locked_hint') }}</p>

    <div class="card-detail__field">
      <label for="edit-name" class="card-detail__label">{{ t('wallet.add.display_name') }}</label>
      <InputText
        id="edit-name"
        v-model="draft.displayName"
        class="w-full"
        :maxlength="100"
      />
    </div>

    <div class="card-detail__field">
      <label for="edit-nickname" class="card-detail__label">{{ t('wallet.detail.nickname') }}</label>
      <InputText
        id="edit-nickname"
        v-model="draft.nickname"
        class="w-full"
        :placeholder="t('wallet.add.nickname_placeholder')"
        :maxlength="50"
      />
    </div>

    <div class="card-detail__field">
      <label for="edit-memo" class="card-detail__label">{{ t('wallet.detail.memo') }}</label>
      <Textarea
        id="edit-memo"
        v-model="draft.memo"
        class="w-full"
        :placeholder="t('wallet.add.memo_placeholder')"
        :maxlength="500"
        :rows="3"
        autoResize
      />
    </div>

    <div class="card-detail__field">
      <label for="edit-order" class="card-detail__label">{{ t('wallet.detail.display_order') }}</label>
      <InputNumber
        id="edit-order"
        v-model="draft.displayOrder"
        class="w-full"
        :min="0"
        :max="9999"
        :use-grouping="false"
      />
    </div>

    <label class="card-detail__checkbox">
      <Checkbox v-model="draft.favorite" binary input-id="edit-favorite" />
      <span>{{ t('wallet.card.favorite') }}</span>
    </label>

    <p v-if="saveError" class="card-detail__error" role="alert">
      {{ saveError }}
    </p>

    <div class="card-detail__edit-footer">
      <Button
        :label="t('wallet.detail.cancel')"
        severity="secondary"
        :disabled="saving"
        class="flex-1"
        @click="emit('cancel')"
      />
      <Button
        :label="saving ? '…' : t('wallet.detail.save')"
        :disabled="saving || !draft.displayName.trim()"
        :loading="saving"
        class="flex-1"
        @click="emit('save')"
      />
    </div>
  </section>
</template>

<style scoped>
.card-detail__section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.card-detail__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.card-detail__label {
  font-size: 0.8125rem;
  font-weight: 600;
}
.card-detail__checkbox {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.9375rem;
  cursor: pointer;
}
.card-detail__hint {
  font-size: 0.8125rem;
  /* 背景・文字色は Tailwind dark: クラス（bg-surface-50 dark:bg-surface-800 text-surface-600 dark:text-surface-300）で追従 */
  margin: 0 0 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
}
.card-detail__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
}
.card-detail__edit-footer {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
</style>
