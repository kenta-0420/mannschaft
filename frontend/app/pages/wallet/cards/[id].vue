<script setup lang="ts">
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'
import CardDetailActions from '~/components/wallet/card-detail/CardDetailActions.vue'
import CardDetailBalanceSection from '~/components/wallet/card-detail/CardDetailBalanceSection.vue'
import CardDetailDeleteModal from '~/components/wallet/card-detail/CardDetailDeleteModal.vue'
import CardDetailEditForm from '~/components/wallet/card-detail/CardDetailEditForm.vue'
import CardDetailMeta from '~/components/wallet/card-detail/CardDetailMeta.vue'
import ShareTokenQrModal from '~/components/wallet/ShareTokenQrModal.vue'
import type { UpdateUserPointCardRequest, UserPointCardDetail } from '~/types/pointCard'

/**
 * F18 カード詳細・編集・削除ページ（設計書 §7.1 / §8.1）。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>バーコードプレビュー大きく表示（カード番号も同時に大きく表示）</li>
 *   <li>編集モードトグル: displayName / nickname / memo / favorite / displayOrder</li>
 *   <li>削除ボタン（確認モーダル必須、物理削除）</li>
 *   <li>カード詳細表示時に自動で {@link useWalletApi#recordUsed} を呼び lastUsedAt を更新（背景・ベストエフォート）</li>
 * </ul>
 *
 * <p>barcodeValue / barcodeFormat は仕様上編集不可（設計書 §6 / §9.3）。
 * 変更したい場合はカード削除 → 再登録という運用にする。UI 上もその旨を明示する。</p>
 *
 * <p>リファクタリング第10弾（2026-05-17）でレイアウト要素を
 * `components/wallet/card-detail/` 配下の子コンポーネントへ分割した。
 * API 呼び出し・state・ハンドラはすべて本ページに残り、振る舞いは完全同一。</p>
 */

import dayjs from 'dayjs'

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const walletApi = useWalletApi()
const runtimeConfig = useRuntimeConfig()
const { userTimezone } = useDatetime()

const cardId = computed(() => route.params.id as string)

// F18 SELF_ISSUED_BALANCE 凍結（2026-05-17 マスター御裁可）
// 資金決済法（前払式支払手段＝自家型）対応のため法務整備が整うまで一時凍結。
// 残高型カードの残高表示は「---」（伏字）に置き換え、停止中バナーを表示する。
// 設計書: docs/features/F18_point_card_wallet.md §1.4 / §16 / §17
const balanceEnabled = computed<boolean>(() => Boolean(runtimeConfig.public.f18BalanceEnabled))

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
const cardIdCopiedToast = ref(false)
const showShareQrModal = ref(false)

/**
 * 編集用ドラフト（編集モード突入時にコピー、保存成功時にカードへ反映）。
 *
 * <p>リファクタリング第10弾で `reactive` から `ref` に変更。
 * 子コンポーネント `CardDetailEditForm` の `defineModel` と双方向バインドする
 * ためで、保持する値・更新タイミング・初期化処理は元実装と等価。</p>
 */
interface CardDetailDraft {
  displayName: string
  nickname: string
  memo: string
  favorite: boolean
  displayOrder: number
}
const draft = ref<CardDetailDraft>({
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
  return dayjs.tz(card.value.lastUsedAt, userTimezone.value).format('YYYY/MM/DD HH:mm')
})

/**
 * 自店発行カード（SELF_ISSUED_BALANCE / SELF_ISSUED_STAMP）の場合のみ
 * 「店舗で提示」ボタンと残高/スタンプ数セクションを表示する。
 * EXTERNAL カードは店舗で押印できないため非表示。
 */
const isSelfIssued = computed(() => {
  const t = card.value?.providerType
  return t === 'SELF_ISSUED_BALANCE' || t === 'SELF_ISSUED_STAMP'
})

const isBalanceType = computed(() => card.value?.providerType === 'SELF_ISSUED_BALANCE')
const isStampType = computed(() => card.value?.providerType === 'SELF_ISSUED_STAMP')

/** 残高表示（JPY フォーマット）。MVP は JPY 固定。
 * F18 BALANCE 凍結中（balanceEnabled=false）は実値を伏字「---」に置換し、
 * 残高 PII を画面表示しない方針とする（API は引き続き値を返すが、UI で隠す）。
 */
const balanceFormatted = computed(() => {
  if (!isBalanceType.value) return ''
  if (!balanceEnabled.value) return '---'
  const v = card.value?.balance
  if (v === null || v === undefined) return ''
  return new Intl.NumberFormat('ja-JP', {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(v)
})

const stampCountValue = computed(() => {
  if (!isStampType.value) return null
  const v = card.value?.stampCount
  return v === null || v === undefined ? null : v
})

// ─────────────────────────────────────────────
// Data loading
// ─────────────────────────────────────────────

async function load() {
  loading.value = true
  loadError.value = false
  try {
    card.value = await walletApi.getCard(cardId.value)
    // カード詳細表示時に last_used_at を自動更新（ベストエフォート・背景処理）
    walletApi.recordUsed(cardId.value)
      .then(() => {
        if (card.value) {
          card.value = { ...card.value, lastUsedAt: new Date().toISOString() }
        }
      })
      .catch((e) => {
        console.warn('[wallet/cards/[id]] auto recordUsed failed (non-blocking)', e)
      })
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
  draft.value = {
    displayName: card.value.displayName,
    nickname: card.value.nickname ?? '',
    memo: card.value.memo ?? '',
    favorite: card.value.favorite,
    displayOrder: card.value.displayOrder,
  }
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
    displayName: draft.value.displayName.trim(),
    nickname: draft.value.nickname.trim() || null,
    memo: draft.value.memo.trim() || null,
    favorite: draft.value.favorite,
    displayOrder: draft.value.displayOrder,
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

/**
 * カード ID をクリップボードにコピー。
 *
 * <p>F18 Phase 2 UC-9（店主が顧客のカード ID を入力して押印するフロー）で、
 * 顧客が店主に自分のカード ID を素早く伝えるための UX。Clipboard API が
 * 使えない環境（古い iOS Safari / HTTP コンテキスト等）は execCommand に
 * フォールバックする。</p>
 */
async function copyCardId() {
  if (!card.value) return
  const id = card.value.id
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(id)
    }
    else {
      // フォールバック: 一時 textarea で execCommand('copy')
      const ta = document.createElement('textarea')
      ta.value = id
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    cardIdCopiedToast.value = true
    setTimeout(() => { cardIdCopiedToast.value = false }, 2000)
  }
  catch (e) {
    console.error('[wallet/cards/[id]] copy card id failed', e)
  }
}

onMounted(load)
</script>

<template>
  <div class="card-detail">
    <PageHeader
      :title="card?.displayName ?? '…'"
      back-to="/wallet"
    />

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

      <!-- 残高 / スタンプ数表示（自店発行カードのみ、表示モードのみ） -->
      <CardDetailBalanceSection
        v-if="!editMode"
        :is-balance-type="isBalanceType"
        :is-stamp-type="isStampType"
        :balance-formatted="balanceFormatted"
        :stamp-count-value="stampCountValue"
        :balance-enabled="balanceEnabled"
      />

      <!-- アクションボタン群（店舗で提示 / お気に入り / 編集・編集モード以外で表示） -->
      <CardDetailActions
        v-if="!editMode"
        :is-self-issued="isSelfIssued"
        :favorite="card.favorite"
        @open-share="showShareQrModal = true"
        @toggle-favorite="toggleFavorite"
        @enter-edit="enterEdit"
      />

      <!-- 表示モード: 詳細 + 任意項目 -->
      <CardDetailMeta
        v-if="!editMode"
        :card="card"
        :last-used-display="lastUsedDisplay"
        @copy-card-id="copyCardId"
      />

      <!-- 編集モード -->
      <CardDetailEditForm
        v-if="editMode"
        v-model:draft="draft"
        :saving="saving"
        :save-error="saveError"
        @cancel="cancelEdit"
        @save="save"
      />

      <!-- 削除ボタン（表示モードのみ） -->
      <section v-if="!editMode" class="card-detail__danger">
        <button
          type="button"
          class="card-detail__btn card-detail__btn--danger bg-white dark:bg-surface-900"
          @click="showDeleteConfirm = true"
        >
          {{ t('wallet.detail.delete') }}
        </button>
      </section>
    </template>

    <!-- カード ID コピートースト -->
    <div
      v-if="cardIdCopiedToast"
      class="card-detail__toast"
      role="status"
      aria-live="polite"
    >
      {{ t('wallet.card_id_copied') }}
    </div>

    <!-- 店舗で提示 QR モーダル -->
    <ShareTokenQrModal
      :card-id="cardId"
      :show="showShareQrModal"
      @close="showShareQrModal = false"
    />

    <!-- 削除確認モーダル -->
    <CardDetailDeleteModal
      :show="showDeleteConfirm"
      :deleting="deleting"
      @cancel="showDeleteConfirm = false"
      @confirm="confirmDelete"
    />
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
.card-detail__loading,
.card-detail__error {
  text-align: center;
  padding: 3rem 1rem;
  color: var(--p-text-muted-color, #6b7280);
}
/* 元実装で `.card-detail__error` の後段定義により color/font-size/margin が上書きされる挙動を維持 */
.card-detail__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
}
.card-detail__section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.card-detail__btn {
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--p-surface-300, #d1d5db);
  /* 背景は Tailwind dark: クラスで追従（デフォルト=var(--p-surface-0)、ダーク=surface-900）。
     danger ボタンは bg-white dark:bg-surface-900 クラスを直接付与 */
  color: var(--p-text-color, #111827);
  font-weight: 600;
  cursor: pointer;
}
.card-detail__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.card-detail__btn--danger {
  /* 背景は Tailwind dark: クラスで追従。文字色・ボーダーは danger 用固定値 */
  color: #dc2626;
  border-color: #dc2626;
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
</style>
