<script setup lang="ts">
import { CURRENT_TERMS_VERSION } from '~/types/pointCard'

/**
 * F18 ウォレット規約同意モーダル。
 *
 * <p>設計書 §9.2 / §8.6 に基づき、4 項目を**個別チェックボックス**で同意させる。
 * 「すべて同意」ボタンはダークパターン回避のため設けない。</p>
 *
 * <p>ADHD 配慮として、本文を最下部までスクロールするまでチェックボックスを disabled に
 * しておく（読み飛ばし防止）。スクロール完了後は全項目チェック可能になる。
 * 4 項目すべてチェックされるまで「同意して開始」ボタンを disabled に保つ。</p>
 *
 * @prop modelValue モーダルの表示状態（v-model）
 * @prop mode 'consent' = 同意フロー、'view' = 過去同意の確認用 read-only
 * @emit update:modelValue モーダルが閉じられたとき
 * @emit accepted 同意が完了したとき（payload は termsVersion）
 */

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    mode?: 'consent' | 'view'
  }>(),
  {
    mode: 'consent',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  accepted: [termsVersion: string]
}>()

const { t } = useI18n()
const appearanceStore = useAppearanceStore()

/** モーダル開いた瞬間にスクロール状態をリセットする */
const scrollEl = ref<HTMLElement | null>(null)
const scrolledToBottom = ref(false)

/** 各項目の同意状態。view モードでは強制 true。 */
const checked = ref<[boolean, boolean, boolean, boolean]>([false, false, false, false])

const isViewMode = computed(() => props.mode === 'view')

/** 全項目チェック済み判定 */
const allChecked = computed(() => checked.value.every((c) => c))

/** チェックボックス有効化判定: スクロール完了後 or view モード */
const canCheck = computed(() => scrolledToBottom.value || isViewMode.value)

/** 「同意して開始」ボタン有効化判定 */
const canSubmit = computed(() => allChecked.value && !isViewMode.value)

function handleScroll() {
  const el = scrollEl.value
  if (!el) return
  // 最下部到達判定（誤差 4px 許容）
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 4) {
    scrolledToBottom.value = true
  }
}

function close() {
  emit('update:modelValue', false)
}

function accept() {
  if (!canSubmit.value) return
  emit('accepted', CURRENT_TERMS_VERSION)
  close()
}

/** モーダル開時に状態リセット（view モードは初期チェック済み） */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      checked.value = isViewMode.value
        ? [true, true, true, true]
        : [false, false, false, false]
      scrolledToBottom.value = isViewMode.value
      // スクロール位置を先頭に戻す
      nextTick(() => {
        scrollEl.value?.scrollTo({ top: 0 })
      })
    }
  },
)

const titleKey = computed(() =>
  isViewMode.value ? 'wallet.terms.view_mode_title' : 'wallet.terms.title',
)
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="terms-modal__backdrop"
      role="dialog"
      :aria-modal="true"
      :aria-label="t(titleKey)"
    >
      <div
        class="terms-modal__panel"
        :class="{ 'terms-modal__panel--dark': appearanceStore.isDark }"
      >
        <header class="terms-modal__header">
          <h2 class="terms-modal__title">{{ t(titleKey) }}</h2>
          <button
            v-if="isViewMode"
            type="button"
            class="terms-modal__close"
            :aria-label="t('wallet.actions.close')"
            @click="close"
          >×</button>
        </header>

        <p v-if="!isViewMode" class="terms-modal__hint">
          {{
            scrolledToBottom
              ? t('wallet.terms.scroll_reached')
              : t('wallet.terms.scroll_hint')
          }}
        </p>

        <div ref="scrollEl" class="terms-modal__scroll" @scroll="handleScroll">
          <ol class="terms-modal__list">
            <li v-for="i in [1, 2, 3, 4]" :key="i" class="terms-modal__item">
              <label class="terms-modal__label">
                <input
                  v-model="checked[i - 1]"
                  type="checkbox"
                  :disabled="!canCheck || isViewMode"
                  class="terms-modal__checkbox"
                >
                <span class="terms-modal__item-text">
                  {{ t(`wallet.terms.item${i}`) }}
                </span>
              </label>
            </li>
          </ol>
        </div>

        <footer class="terms-modal__footer">
          <button
            v-if="isViewMode"
            type="button"
            class="terms-modal__btn terms-modal__btn--primary"
            @click="close"
          >
            {{ t('wallet.actions.close') }}
          </button>
          <template v-else>
            <button type="button" class="terms-modal__btn" @click="close">
              {{ t('wallet.actions.cancel') }}
            </button>
            <button
              type="button"
              class="terms-modal__btn terms-modal__btn--primary"
              :disabled="!canSubmit"
              @click="accept"
            >
              {{ t('wallet.terms.accept') }}
            </button>
          </template>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.terms-modal__backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 1rem;
}
.terms-modal__panel {
  background: var(--p-surface-0, #fff);
  color: var(--p-text-color, #111827);
  border-radius: 1rem;
  max-width: 540px;
  width: 100%;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
}
.terms-modal__panel--dark {
  background: #1e1e1e;
  color: #f4f4f5;
  color-scheme: dark;
}
.terms-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem 1.5rem 0.75rem;
}
.terms-modal__title {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
}
.terms-modal__close {
  background: none;
  border: none;
  font-size: 1.5rem;
  cursor: pointer;
  color: var(--p-text-muted-color, #6b7280);
  padding: 0 0.5rem;
}
.terms-modal__panel--dark .terms-modal__close,
.terms-modal__panel--dark .terms-modal__hint {
  color: #a1a1aa;
}
.terms-modal__hint {
  padding: 0 1.5rem;
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0 0 0.75rem;
}
.terms-modal__scroll {
  overflow-y: auto;
  padding: 0 1.5rem;
  flex: 1;
  min-height: 200px;
}
.terms-modal__list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.terms-modal__item {
  padding: 0.875rem 0;
  border-bottom: 1px solid var(--p-surface-200, #e5e7eb);
}
.terms-modal__panel--dark .terms-modal__item {
  border-bottom-color: #3f3f46;
}
.terms-modal__item:last-child {
  border-bottom: none;
}
.terms-modal__label {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  cursor: pointer;
}
.terms-modal__label:has(input:disabled) {
  cursor: not-allowed;
  opacity: 0.6;
}
.terms-modal__checkbox {
  margin-top: 0.25rem;
  flex-shrink: 0;
  width: 1.125rem;
  height: 1.125rem;
}
.terms-modal__item-text {
  font-size: 0.9375rem;
  line-height: 1.6;
}
.terms-modal__footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  padding: 1rem 1.5rem 1.25rem;
  border-top: 1px solid var(--p-surface-200, #e5e7eb);
}
.terms-modal__panel--dark .terms-modal__footer {
  border-top-color: #3f3f46;
}
.terms-modal__btn {
  padding: 0.625rem 1.25rem;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  border: 1px solid var(--p-surface-300, #d1d5db);
  background: var(--p-surface-0, #fff);
  color: var(--p-text-color, #111827);
}
.terms-modal__panel--dark .terms-modal__btn {
  background: #27272a;
  border-color: #3f3f46;
  color: #f4f4f5;
}
.terms-modal__btn--primary {
  background: var(--p-primary-color, #3b82f6);
  border-color: var(--p-primary-color, #3b82f6);
  color: #fff;
}
.terms-modal__panel--dark .terms-modal__btn--primary {
  background: var(--p-primary-color, #3b82f6);
  border-color: var(--p-primary-color, #3b82f6);
  color: #fff;
}
.terms-modal__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
