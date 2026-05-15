<script setup lang="ts">
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'
import type { UpdateUserPointCardRequest, UserPointCardDetail } from '~/types/pointCard'

/**
 * F18 カード詳細・編集・削除ページ（設計書 §7.1 / §8.1）。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>バーコードプレビュー大きく表示（カード番号も同時に大きく表示）</li>
 *   <li>編集モードトグル: displayName / nickname / memo / favorite / displayOrder</li>
 *   <li>削除ボタン（確認モーダル必須、物理削除）</li>
 *   <li>「使用済み」ボタンで {@link useWalletApi#recordUsed} を呼び lastUsedAt を更新</li>
 * </ul>
 *
 * <p>barcodeValue / barcodeFormat は仕様上編集不可（設計書 §6 / §9.3）。
 * 変更したい場合はカード削除 → 再登録という運用にする。UI 上もその旨を明示する。</p>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const walletApi = useWalletApi()

const cardId = computed(() => route.params.id as string)

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

const card = ref<UserPointCardDetail | null>(null)
const loading = ref(true)
const loadError = ref(false)

const editMode = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)

const showDeleteConfirm = ref(false)
const deleting = ref(false)
const recordingUsed = ref(false)
const usedToast = ref(false)

// 編集用ドラフト（編集モード突入時にコピー、保存成功時にカードへ反映）
const draft = reactive({
  displayName: '',
  nickname: '',
  memo: '',
  favorite: false,
  displayOrder: 0,
})

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

const lastUsedDisplay = computed(() => {
  if (!card.value?.lastUsedAt) return '—'
  const d = new Date(card.value.lastUsedAt)
  return d.toLocaleString()
})

// ─────────────────────────────────────────────
// Data loading
// ─────────────────────────────────────────────

async function load() {
  loading.value = true
  loadError.value = false
  try {
    card.value = await walletApi.getCard(cardId.value)
  }
  catch (e) {
    console.error('[wallet/cards/[id]] load failed', e)
    loadError.value = true
  }
  finally {
    loading.value = false
  }
}

// ─────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────

function enterEdit() {
  if (!card.value) return
  draft.displayName = card.value.displayName
  draft.nickname = card.value.nickname ?? ''
  draft.memo = card.value.memo ?? ''
  draft.favorite = card.value.favorite
  draft.displayOrder = card.value.displayOrder
  saveError.value = null
  editMode.value = true
}

function cancelEdit() {
  editMode.value = false
  saveError.value = null
}

async function save() {
  if (!card.value || saving.value) return
  saving.value = true
  saveError.value = null
  const body: UpdateUserPointCardRequest = {
    displayName: draft.displayName.trim(),
    nickname: draft.nickname.trim() || null,
    memo: draft.memo.trim() || null,
    favorite: draft.favorite,
    displayOrder: draft.displayOrder,
  }
  try {
    card.value = await walletApi.updateCard(cardId.value, body)
    editMode.value = false
  }
  catch (e) {
    console.error('[wallet/cards/[id]] save failed', e)
    saveError.value = t('wallet.detail.save_failed')
  }
  finally {
    saving.value = false
  }
}

async function toggleFavorite() {
  if (!card.value || saving.value) return
  saving.value = true
  try {
    card.value = await walletApi.updateCard(cardId.value, {
      favorite: !card.value.favorite,
    })
  }
  catch (e) {
    console.error('[wallet/cards/[id]] favorite toggle failed', e)
  }
  finally {
    saving.value = false
  }
}

async function recordUsed() {
  if (!card.value || recordingUsed.value) return
  recordingUsed.value = true
  try {
    await walletApi.recordUsed(cardId.value)
    // 楽観的に lastUsedAt を更新（API が成功した時点で表示更新）
    card.value = { ...card.value, lastUsedAt: new Date().toISOString() }
    usedToast.value = true
    setTimeout(() => { usedToast.value = false }, 2500)
  }
  catch (e) {
    console.error('[wallet/cards/[id]] recordUsed failed', e)
  }
  finally {
    recordingUsed.value = false
  }
}

async function confirmDelete() {
  if (deleting.value) return
  deleting.value = true
  try {
    await walletApi.deleteCard(cardId.value)
    await router.push('/wallet')
  }
  catch (e) {
    console.error('[wallet/cards/[id]] delete failed', e)
    deleting.value = false
  }
}

function backToWallet() {
  router.push('/wallet')
}

onMounted(load)
</script>

<template>
  <div class="card-detail">
    <header class="card-detail__header">
      <button
        type="button"
        class="card-detail__back"
        :aria-label="t('wallet.detail.back_to_list')"
        @click="backToWallet"
      >
        ←
      </button>
      <h1 class="card-detail__title">
        {{ card?.displayName ?? '…' }}
      </h1>
    </header>

    <div v-if="loading" class="card-detail__loading">…</div>

    <div v-else-if="loadError || !card" class="card-detail__error" role="alert">
      <p>{{ t('wallet.detail.not_found') }}</p>
      <button type="button" class="card-detail__btn" @click="backToWallet">
        {{ t('wallet.detail.back_to_list') }}
      </button>
    </div>

    <template v-else>
      <!-- バーコードプレビュー（編集モードでも表示する） -->
      <section class="card-detail__section">
        <BarcodePreview
          :value="card.barcodeValue"
          :format="card.barcodeFormat"
          size="large"
        />
      </section>

      <!-- 使用済み記録ボタン（編集モード以外で表示） -->
      <section v-if="!editMode" class="card-detail__actions">
        <button
          type="button"
          class="card-detail__btn card-detail__btn--primary"
          :disabled="recordingUsed"
          @click="recordUsed"
        >
          {{ t('wallet.detail.record_used') }}
        </button>
        <button
          type="button"
          class="card-detail__btn"
          @click="toggleFavorite"
        >
          {{ card.favorite ? t('wallet.detail.favorite_off') : t('wallet.detail.favorite_on') }}
        </button>
        <button
          type="button"
          class="card-detail__btn"
          @click="enterEdit"
        >
          {{ t('wallet.detail.edit') }}
        </button>
      </section>

      <!-- 表示モード: 詳細 -->
      <section v-if="!editMode" class="card-detail__section">
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
          <div class="card-detail__dl-row">
            <dt>{{ t('wallet.detail.last_used_at') }}</dt>
            <dd>{{ lastUsedDisplay }}</dd>
          </div>
        </dl>
      </section>

      <section v-if="!editMode && (card.nickname || card.memo)" class="card-detail__section">
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

      <!-- 編集モード -->
      <section v-if="editMode" class="card-detail__section">
        <p class="card-detail__hint">{{ t('wallet.detail.barcode_locked_hint') }}</p>

        <div class="card-detail__field">
          <label for="edit-name" class="card-detail__label">{{ t('wallet.add.display_name') }}</label>
          <input
            id="edit-name"
            v-model="draft.displayName"
            type="text"
            class="card-detail__input"
            maxlength="100"
          >
        </div>

        <div class="card-detail__field">
          <label for="edit-nickname" class="card-detail__label">{{ t('wallet.detail.nickname') }}</label>
          <input
            id="edit-nickname"
            v-model="draft.nickname"
            type="text"
            class="card-detail__input"
            :placeholder="t('wallet.add.nickname_placeholder')"
            maxlength="50"
          >
        </div>

        <div class="card-detail__field">
          <label for="edit-memo" class="card-detail__label">{{ t('wallet.detail.memo') }}</label>
          <textarea
            id="edit-memo"
            v-model="draft.memo"
            class="card-detail__input card-detail__textarea"
            :placeholder="t('wallet.add.memo_placeholder')"
            maxlength="500"
            rows="3"
          />
        </div>

        <div class="card-detail__field">
          <label for="edit-order" class="card-detail__label">{{ t('wallet.detail.display_order') }}</label>
          <input
            id="edit-order"
            v-model.number="draft.displayOrder"
            type="number"
            class="card-detail__input"
            min="0"
            max="9999"
          >
        </div>

        <label class="card-detail__checkbox">
          <input v-model="draft.favorite" type="checkbox">
          <span>{{ t('wallet.card.favorite') }}</span>
        </label>

        <p v-if="saveError" class="card-detail__error" role="alert">
          {{ saveError }}
        </p>

        <div class="card-detail__edit-footer">
          <button type="button" class="card-detail__btn" :disabled="saving" @click="cancelEdit">
            {{ t('wallet.detail.cancel') }}
          </button>
          <button
            type="button"
            class="card-detail__btn card-detail__btn--primary"
            :disabled="saving || !draft.displayName.trim()"
            @click="save"
          >
            {{ saving ? '…' : t('wallet.detail.save') }}
          </button>
        </div>
      </section>

      <!-- 削除ボタン（表示モードのみ） -->
      <section v-if="!editMode" class="card-detail__danger">
        <button
          type="button"
          class="card-detail__btn card-detail__btn--danger"
          @click="showDeleteConfirm = true"
        >
          {{ t('wallet.detail.delete') }}
        </button>
      </section>
    </template>

    <!-- 使用済みトースト -->
    <div
      v-if="usedToast"
      class="card-detail__toast"
      role="status"
      aria-live="polite"
    >
      {{ t('wallet.detail.used_recorded') }}
    </div>

    <!-- 削除確認モーダル -->
    <div
      v-if="showDeleteConfirm"
      class="card-detail__modal-backdrop"
      role="dialog"
      aria-modal="true"
      :aria-label="t('wallet.detail.delete_confirm_title')"
      @click.self="showDeleteConfirm = false"
    >
      <div class="card-detail__modal">
        <h2 class="card-detail__modal-title">{{ t('wallet.detail.delete_confirm_title') }}</h2>
        <p class="card-detail__modal-body">{{ t('wallet.detail.delete_confirm') }}</p>
        <div class="card-detail__modal-actions">
          <button
            type="button"
            class="card-detail__btn"
            :disabled="deleting"
            @click="showDeleteConfirm = false"
          >
            {{ t('wallet.detail.cancel') }}
          </button>
          <button
            type="button"
            class="card-detail__btn card-detail__btn--danger"
            :disabled="deleting"
            @click="confirmDelete"
          >
            {{ deleting ? '…' : t('wallet.detail.delete_confirm_ok') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card-detail {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  position: relative;
}
.card-detail__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.card-detail__back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--p-surface-100, #f3f4f6);
  border: none;
  cursor: pointer;
  font-size: 1.25rem;
}
.card-detail__title {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-detail__loading,
.card-detail__error {
  text-align: center;
  padding: 3rem 1rem;
  color: var(--p-text-muted-color, #6b7280);
}
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
.card-detail__actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.card-detail__btn {
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--p-surface-300, #d1d5db);
  background: var(--p-surface-0, #fff);
  color: var(--p-text-color, #111827);
  font-weight: 600;
  cursor: pointer;
}
.card-detail__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.card-detail__btn--primary {
  background: var(--p-primary-color, #3b82f6);
  color: #fff;
  border-color: transparent;
}
.card-detail__btn--danger {
  background: #fff;
  color: #dc2626;
  border-color: #dc2626;
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
.card-detail__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.card-detail__label {
  font-size: 0.8125rem;
  font-weight: 600;
}
.card-detail__input {
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--p-surface-300, #d1d5db);
  border-radius: 0.5rem;
  background: var(--p-surface-0, #fff);
  font-size: 0.9375rem;
}
.card-detail__textarea {
  resize: vertical;
  font-family: inherit;
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
  color: var(--p-text-muted-color, #6b7280);
  margin: 0 0 0.5rem;
  padding: 0.5rem 0.75rem;
  background: var(--p-surface-50, #f9fafb);
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
.card-detail__edit-footer .card-detail__btn {
  flex: 1;
}
.card-detail__danger {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--p-surface-200, #e5e7eb);
}
.card-detail__toast {
  position: fixed;
  bottom: 2rem;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(17, 24, 39, 0.92);
  color: #fff;
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  z-index: 20;
}
.card-detail__modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 30;
  padding: 1rem;
}
.card-detail__modal {
  background: #fff;
  border-radius: 0.75rem;
  padding: 1.25rem;
  max-width: 420px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.card-detail__modal-title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0;
}
.card-detail__modal-body {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--p-text-color, #111827);
}
.card-detail__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
