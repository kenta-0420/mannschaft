<script setup lang="ts">
import type { LocationQueryValue } from 'vue-router'
import BarcodeCapture from '~/components/wallet/BarcodeCapture.vue'
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'
import PresetCardButtons from '~/components/wallet/PresetCardButtons.vue'
import type { BarcodeFormat, PointCardProvider } from '~/types/pointCard'

/**
 * F18 カード追加ウィザード（設計書 §7.2 / §8.1）。
 *
 * <p>5 ステップで構成（ただし UI 上は 2 ステップに集約）:</p>
 * <ol>
 *   <li>プリセットボタン（人気 10 社）+ 自由入力モード</li>
 *   <li>バーコード入力方法: カメラ / 画像 / 手入力 の 3 択</li>
 *   <li>プレビュー画面（jsbarcode で再描画）</li>
 *   <li>任意項目（nickname / memo / favorite）入力</li>
 *   <li>保存（{@link useWalletApi#createCard}）</li>
 * </ol>
 *
 * <p>「step 1: 入力」と「step 2: プレビュー & 任意項目」の 2 画面構成にして
 * ADHD ユーザーに優しい一画面完結に近づけている。step 1 に必須項目（displayName +
 * バーコード値 / NONE 選択）を全部置き、step 2 で確認 + 任意項目を入力する。</p>
 *
 * <p>F18 SELF_ISSUED_BALANCE 凍結（2026-05-17 マスター御裁可）:</p>
 * <ul>
 *   <li>このページはユーザー個人が他社（EXTERNAL）カードを追加する経路であり、
 *       SELF_ISSUED_BALANCE / SELF_ISSUED_STAMP の選択肢自体を持たない（type は
 *       サーバー側で fuzzy match の結果として決まる）。プリセットカードも EXTERNAL の
 *       人気事業者マスタのみ。よって本ページ単体での凍結ガードは不要。</li>
 *   <li>自店発行プロバイダーへのカード紐付けは {@code /wallet/add-from-qr} 経由のため、
 *       残高型プロバイダーが今後 SELF_ISSUED_BALANCE で発行された場合の追加経路は
 *       別途検討する（現状そもそも管理 UI からは STAMP のみ作成可能）。</li>
 * </ul>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const walletApi = useWalletApi()

// ─────────────────────────────────────────────
// Query 事前充填（F18 Phase 2 UC-8 互換）
// ─────────────────────────────────────────────
// QR 経由で `/wallet/cards/new?providerId=...&name=...&format=...` で開かれた場合に
// 自由入力モードを初期値プリセット状態で開く。専用ページ /wallet/add-from-qr を使うほうが
// UX が良いが、PWA でないブラウザや古い QR スキャナアプリが従来 URL でこのページを叩いて
// くる可能性もあるためフォールバックとして対応しておく。
function pickQueryString(
  value: LocationQueryValue | LocationQueryValue[] | undefined,
): string {
  if (value == null) return ''
  if (Array.isArray(value)) {
    const first = value[0]
    return first ?? ''
  }
  return value
}
const initialDisplayName = pickQueryString(route.query.name)
const initialFormatRaw = pickQueryString(route.query.format).toUpperCase()
const KNOWN_FORMATS_FOR_QUERY: BarcodeFormat[] = [
  'CODE128', 'CODE39', 'EAN13', 'EAN8', 'JAN13', 'ITF', 'QR', 'PDF417', 'NONE',
]
const initialFormat: BarcodeFormat
  = (KNOWN_FORMATS_FOR_QUERY as string[]).includes(initialFormatRaw)
    ? (initialFormatRaw as BarcodeFormat)
    : 'CODE128'

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

type Step = 'input' | 'preview'
const step = ref<Step>('input')

const displayName = ref(initialDisplayName)
const barcodeValue = ref('')
const barcodeFormat = ref<BarcodeFormat>(initialFormat)

const nickname = ref('')
const memo = ref('')
const favorite = ref(false)

const saving = ref(false)
const saveError = ref<string | null>(null)
const validationError = ref<string | null>(null)

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

const canProceedToPreview = computed(() => {
  if (!displayName.value.trim()) return false
  if (barcodeFormat.value === 'NONE') return true
  return barcodeValue.value.trim().length > 0
})

// ─────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────

/** プリセットボタンがタップされたとき: displayName / barcodeFormat を事前充填 */
function onPresetSelected(provider: PointCardProvider) {
  // 既に入力されているフィールドは尊重しない（明示的な意思表示として上書きする）。
  // 設計書 §7.6.5 に「プリセットタップ = 明示的な意思表示として最優先」とある。
  displayName.value = provider.displayName
  if (provider.defaultBarcodeFormat) {
    barcodeFormat.value = provider.defaultBarcodeFormat
  }
  // 入力欄にフォーカスを移すため、自由入力セクションへスクロール
  nextTick(() => {
    const el = document.getElementById('card-displayname')
    el?.focus()
  })
}

/** BarcodeCapture からの検出: 値と形式を取り込む */
function onBarcodeDetected(value: string, format: BarcodeFormat) {
  barcodeValue.value = value
  barcodeFormat.value = format
}

/** 「バーコードなし」が選ばれた場合: format を NONE にして値クリア */
function onNoBarcode() {
  barcodeValue.value = ''
  barcodeFormat.value = 'NONE'
}

function goPreview() {
  validationError.value = null
  if (!displayName.value.trim()) {
    validationError.value = t('wallet.add.validation_required')
    return
  }
  if (barcodeFormat.value !== 'NONE' && !barcodeValue.value.trim()) {
    validationError.value = t('wallet.add.validation_barcode_required')
    return
  }
  step.value = 'preview'
}

function backToInput() {
  step.value = 'input'
}

async function save() {
  if (saving.value) return
  saving.value = true
  saveError.value = null
  try {
    const created = await walletApi.createCard({
      displayName: displayName.value.trim(),
      barcodeValue: barcodeValue.value.trim() || '', // NONE の場合は空文字
      barcodeFormat: barcodeFormat.value,
      nickname: nickname.value.trim() || null,
      memo: memo.value.trim() || null,
      favorite: favorite.value,
    })
    // 詳細ページに遷移
    await router.push(`/wallet/cards/${created.id}`)
  }
  catch (e) {
    console.error('[wallet/cards/new] save failed', e)
    saveError.value = t('wallet.add.save_failed')
  }
  finally {
    saving.value = false
  }
}

function cancel() {
  router.back()
}
</script>

<template>
  <div class="card-new">
    <PageHeader :title="t('wallet.add.title')" />

    <!-- ===== Step 1: 入力 ===== -->
    <template v-if="step === 'input'">
      <!-- プリセット -->
      <section class="card-new__section">
        <h2 class="card-new__section-title">{{ t('wallet.add.preset_hint') }}</h2>
        <PresetCardButtons @select="onPresetSelected" />
      </section>

      <!-- 自由入力: カード名 -->
      <section class="card-new__section">
        <h2 class="card-new__section-title">{{ t('wallet.add.manual_section') }}</h2>
        <div class="card-new__field">
          <label for="card-displayname" class="card-new__label">
            {{ t('wallet.add.display_name') }} <span class="card-new__required">*</span>
          </label>
          <InputText
            id="card-displayname"
            v-model="displayName"
            class="w-full"
            :placeholder="t('wallet.add.display_name_placeholder')"
            :maxlength="100"
          />
        </div>
      </section>

      <!-- バーコード入力 -->
      <section class="card-new__section">
        <h2 class="card-new__section-title">{{ t('wallet.add.method') }}</h2>
        <BarcodeCapture
          @detected="onBarcodeDetected"
          @no-barcode="onNoBarcode"
        />
        <!-- 既に値が入っている場合は確認用にミニプレビュー -->
        <div v-if="barcodeValue || barcodeFormat === 'NONE'" class="card-new__mini-preview">
          <BarcodePreview :value="barcodeValue" :format="barcodeFormat" size="normal" />
        </div>
      </section>

      <!-- バリデーション -->
      <p v-if="validationError" class="card-new__error" role="alert">
        {{ validationError }}
      </p>

      <!-- フッタ -->
      <div class="card-new__footer">
        <Button :label="t('wallet.add.cancel')" severity="secondary" class="flex-1" @click="cancel" />
        <Button
          :label="t('wallet.add.next')"
          class="flex-1"
          :disabled="!canProceedToPreview"
          @click="goPreview"
        />
      </div>
    </template>

    <!-- ===== Step 2: プレビュー & 任意項目 ===== -->
    <template v-else>
      <section class="card-new__section">
        <h2 class="card-new__section-title">{{ t('wallet.add.preview_title') }}</h2>
        <p class="card-new__hint">{{ t('wallet.add.preview_hint') }}</p>
        <BarcodePreview :value="barcodeValue" :format="barcodeFormat" size="large" />
        <p class="card-new__preview-name">
          <span class="card-new__preview-name-label">{{ t('wallet.add.preview_card_name') }}:</span>
          <strong>{{ displayName }}</strong>
        </p>
      </section>

      <section class="card-new__section">
        <h2 class="card-new__section-title">{{ t('wallet.add.optional_section') }}</h2>

        <div class="card-new__field">
          <label for="card-nickname" class="card-new__label">{{ t('wallet.add.nickname') }}</label>
          <InputText
            id="card-nickname"
            v-model="nickname"
            class="w-full"
            :placeholder="t('wallet.add.nickname_placeholder')"
            :maxlength="50"
          />
        </div>

        <div class="card-new__field">
          <label for="card-memo" class="card-new__label">{{ t('wallet.add.memo') }}</label>
          <Textarea
            id="card-memo"
            v-model="memo"
            class="w-full"
            :placeholder="t('wallet.add.memo_placeholder')"
            :maxlength="500"
            :rows="3"
            auto-resize
          />
        </div>

        <div class="flex items-center gap-2">
          <Checkbox v-model="favorite" binary input-id="card-favorite" />
          <label for="card-favorite">{{ t('wallet.add.favorite') }}</label>
        </div>
      </section>

      <p v-if="saveError" class="card-new__error" role="alert">
        {{ saveError }}
      </p>

      <div class="card-new__footer">
        <Button
          :label="t('wallet.add.back')"
          severity="secondary"
          class="flex-1"
          :disabled="saving"
          @click="backToInput"
        />
        <Button
          :label="t('wallet.add.save')"
          class="flex-1"
          :disabled="saving"
          :loading="saving"
          @click="save"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.card-new {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.card-new__section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.card-new__section-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0;
}
.card-new__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.card-new__label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--p-text-color, #111827);
}
.card-new__required {
  color: #dc2626;
}
.card-new__mini-preview {
  margin-top: 0.5rem;
}
.card-new__hint {
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.card-new__preview-name {
  margin: 0;
  font-size: 1rem;
  text-align: center;
}
.card-new__preview-name-label {
  color: var(--p-text-muted-color, #6b7280);
  font-size: 0.8125rem;
  margin-right: 0.375rem;
}
.card-new__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
  text-align: center;
}
.card-new__footer {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--p-surface-200, #e5e7eb);
}
</style>
