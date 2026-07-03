<script setup lang="ts">
import type { LocationQueryValue } from 'vue-router'
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'
import TermsAcceptModal from '~/components/wallet/TermsAcceptModal.vue'
import type {
  BarcodeFormat,
  CreateUserPointCardRequest,
  PointCardUserSettings,
} from '~/types/pointCard'

/**
 * F18 Phase 2 UC-8: 顧客が店舗の QR コードから自店ポイントカードを追加するページ。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / §8
 *
 * <p>店主が組織管理画面で生成したディープリンク
 * {@code mannschaft://wallet/add-from-qr?orgId=...&providerId=...&name=...&format=...}
 * を顧客がスマホでスキャンすると本ページが開かれる。</p>
 *
 * <p>処理フロー:</p>
 * <ol>
 *   <li>クエリパラメータから {@code orgId} / {@code providerId} / {@code name} / {@code format} を取得</li>
 *   <li>{@code auth} ミドルウェアが未認証時は {@code /login?redirect=...} へ自動リダイレクト</li>
 *   <li>{@code useWalletApi.getSettings} でウォレット機能の有効化状態を確認し、
 *       未有効化なら {@link TermsAcceptModal} を強制表示</li>
 *   <li>規約同意済みになったら、displayName を事前充填したカード追加フォームを表示</li>
 *   <li>顧客は会員番号があれば入力し、無ければ「番号なし」を選んで保存</li>
 *   <li>保存後 {@code /wallet} に遷移</li>
 * </ol>
 *
 * <p>MVP 設計判断: プロバイダー詳細 API は組織所属チェックがかかっており
 * 顧客（未所属ユーザー）が呼べない可能性が高いため、QR の URL に必要な情報を
 * 直接埋め込む方式とする（{@code name} = displayName / {@code format} = barcodeFormat）。
 * バックエンドの fuzzy match に displayName を渡して provider を解決する。</p>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const walletApi = useWalletApi()

// ─────────────────────────────────────────────
// クエリパラメータの取得
// ─────────────────────────────────────────────

/** クエリから単一文字列値を取り出すユーティリティ（配列だった場合は先頭値、null は空文字）。 */
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

// 注: 将来のプロバイダー詳細 API 呼び出し（公開エンドポイント実装後）で `orgId` を使う予定。
// MVP では providerId のみで displayName を fuzzy match に任せるため、`orgId` は読み取らない。
const providerId = computed(() => pickQueryString(route.query.providerId))
const providerNameFromQuery = computed(() => pickQueryString(route.query.name))
const orgNameFromQuery = computed(() => pickQueryString(route.query.orgName))

/** クエリで渡されたバーコード形式（既知の値域にマッチするものだけ受け入れる）。 */
const KNOWN_FORMATS: BarcodeFormat[] = [
  'CODE128',
  'CODE39',
  'EAN13',
  'EAN8',
  'JAN13',
  'ITF',
  'QR',
  'PDF417',
  'NONE',
]

function isKnownFormat(value: string): value is BarcodeFormat {
  return (KNOWN_FORMATS as string[]).includes(value)
}

const formatFromQuery = computed<BarcodeFormat>(() => {
  const raw = pickQueryString(route.query.format).toUpperCase()
  if (raw && isKnownFormat(raw)) return raw
  return 'CODE128'
})

/** 必須パラメータが揃っているか。`providerId` が空なら QR 不正扱い。 */
const paramsValid = computed(() => providerId.value.trim().length > 0)

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

const settings = ref<PointCardUserSettings | null>(null)
const loadingSettings = ref(true)
const settingsError = ref<string | null>(null)
const showTerms = ref(false)

const displayName = ref('')
const barcodeValue = ref('')
const barcodeFormat = ref<BarcodeFormat>('CODE128')
const noBarcode = ref(false)

const saving = ref(false)
const saveError = ref<string | null>(null)
const savedToast = ref(false)

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

const isWalletReady = computed(
  () => settings.value?.isEnabled === true && settings.value?.termsAcceptedAt != null,
)

const titleText = computed(() => {
  const name = providerNameFromQuery.value.trim()
  if (name) return t('wallet.add_from_qr.title', { providerName: name })
  return t('wallet.add_from_qr.title_fallback')
})

const introText = computed(() => {
  const name = providerNameFromQuery.value.trim()
  if (name) return t('wallet.add_from_qr.intro', { providerName: name })
  return t('wallet.add_from_qr.intro_fallback')
})

const orgSubtitle = computed(() => {
  const name = orgNameFromQuery.value.trim()
  if (!name) return ''
  return t('wallet.add_from_qr.from_org', { orgName: name })
})

const canSave = computed(() => {
  if (!isWalletReady.value) return false
  if (!displayName.value.trim()) return false
  if (noBarcode.value) return true
  // バーコード番号は任意。空欄のままなら NONE として登録できる
  return true
})

// 「番号なし」を選んだら format=NONE / value="" に固定
watch(noBarcode, (next) => {
  if (next) {
    barcodeValue.value = ''
    barcodeFormat.value = 'NONE'
  }
  else {
    // 解除時はクエリから来た format を復元（無ければ CODE128）
    barcodeFormat.value = formatFromQuery.value
  }
})

// ─────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────

async function loadSettings() {
  loadingSettings.value = true
  settingsError.value = null
  try {
    settings.value = await walletApi.getSettings()
    if (!isWalletReady.value) {
      showTerms.value = true
    }
  }
  catch (e) {
    console.error('[wallet/add-from-qr] failed to load settings', e)
    settingsError.value = t('wallet.errors.load_failed')
  }
  finally {
    loadingSettings.value = false
  }
}

onMounted(async () => {
  // クエリから事前充填
  displayName.value = providerNameFromQuery.value
  barcodeFormat.value = formatFromQuery.value
  await loadSettings()
})

// ─────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────

async function onTermsAccepted(termsVersion: string) {
  try {
    settings.value = await walletApi.updateSettings({
      isEnabled: true,
      termsVersion,
    })
    showTerms.value = false
  }
  catch (e) {
    console.error('[wallet/add-from-qr] failed to accept terms', e)
    settingsError.value = t('wallet.errors.update_failed')
  }
}

async function save() {
  if (saving.value || !canSave.value) return
  saving.value = true
  saveError.value = null
  try {
    const body: CreateUserPointCardRequest = {
      displayName: displayName.value.trim(),
      barcodeValue: noBarcode.value ? '' : barcodeValue.value.trim(),
      barcodeFormat: noBarcode.value ? 'NONE' : barcodeFormat.value,
      nickname: null,
      memo: null,
      favorite: false,
    }
    // 注: 現状の CreateUserPointCardRequest 型には providerId フィールドが無いため、
    // displayName で fuzzy match に任せる（設計書 §6 / 出陣書記載の MVP 方針）。
    // バックエンド側で providerId 受け入れが Phase 2 で追加された場合は別陣で対応。
    await walletApi.createCard(body)
    savedToast.value = true
    // 軽くトーストを見せてから wallet に戻る
    setTimeout(() => {
      router.push('/wallet')
    }, 800)
  }
  catch (e) {
    console.error('[wallet/add-from-qr] save failed', e)
    saveError.value = t('wallet.add.save_failed')
    saving.value = false
  }
}

function cancel() {
  router.push('/wallet')
}
</script>

<template>
  <div class="qr-add">
    <PageHeader :title="titleText" />

    <p v-if="orgSubtitle" class="qr-add__org">{{ orgSubtitle }}</p>

    <!-- パラメータ不正 -->
    <div v-if="!paramsValid" class="qr-add__error" role="alert">
      <p>{{ t('wallet.add_from_qr.missing_params') }}</p>
      <Button :label="t('wallet.detail.back_to_list')" severity="secondary" @click="cancel" />
    </div>

    <template v-else>
      <!-- 設定読込中 -->
      <div v-if="loadingSettings" class="qr-add__loading">…</div>

      <div v-else-if="settingsError" class="qr-add__error" role="alert">
        <p>{{ settingsError }}</p>
      </div>

      <!-- 規約未同意（モーダル経由で同意してもらう。未同意のうちはフォームを隠す） -->
      <div v-else-if="!isWalletReady" class="qr-add__loading">…</div>

      <!-- ウォレット有効化済み → 追加フォーム -->
      <template v-else>
        <p class="qr-add__intro">{{ introText }}</p>

        <section class="qr-add__section">
          <label for="qr-displayname" class="qr-add__label">
            {{ t('wallet.add.display_name') }}
          </label>
          <InputText
            id="qr-displayname"
            v-model="displayName"
            class="w-full"
            :placeholder="t('wallet.add.display_name_placeholder')"
            :maxlength="100"
          />
        </section>

        <section class="qr-add__section">
          <label for="qr-barcode" class="qr-add__label">
            {{ t('wallet.add_from_qr.barcode_input') }}
          </label>
          <InputText
            id="qr-barcode"
            v-model="barcodeValue"
            class="w-full"
            :placeholder="t('wallet.add.manual_input_placeholder')"
            :disabled="noBarcode"
            inputmode="numeric"
            autocomplete="off"
          />
          <p class="qr-add__hint">{{ t('wallet.add_from_qr.barcode_hint') }}</p>

          <div class="flex items-center gap-2 mt-1">
            <Checkbox v-model="noBarcode" binary input-id="qr-no-barcode" />
            <label for="qr-no-barcode">{{ t('wallet.add_from_qr.no_barcode') }}</label>
          </div>
        </section>

        <!-- 入力した番号のミニプレビュー -->
        <section v-if="barcodeValue.trim() && !noBarcode" class="qr-add__section">
          <BarcodePreview :value="barcodeValue" :format="barcodeFormat" size="normal" />
        </section>

        <p v-if="saveError" class="qr-add__error-text" role="alert">
          {{ saveError }}
        </p>

        <div class="qr-add__footer">
          <Button
            :label="t('wallet.actions.cancel')"
            severity="secondary"
            class="flex-1"
            :disabled="saving"
            @click="cancel"
          />
          <Button
            :label="t('wallet.add_from_qr.add')"
            class="flex-1"
            :disabled="!canSave || saving"
            :loading="saving"
            @click="save"
          />
        </div>
      </template>
    </template>

    <!-- 保存完了トースト -->
    <div
      v-if="savedToast"
      class="qr-add__toast"
      role="status"
      aria-live="polite"
    >
      {{ t('wallet.add_from_qr.added') }}
    </div>

    <!-- 規約同意モーダル（未有効化時に強制表示） -->
    <TermsAcceptModal
      v-model="showTerms"
      mode="consent"
      @accepted="onTermsAccepted"
    />
  </div>
</template>

<style scoped>
.qr-add {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  position: relative;
}
.qr-add__org {
  margin: 0;
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
}
.qr-add__intro {
  margin: 0;
  font-size: 0.9375rem;
  line-height: 1.5;
}
.qr-add__loading {
  text-align: center;
  padding: 2rem 1rem;
  color: var(--p-text-muted-color, #6b7280);
}
.qr-add__section {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.qr-add__label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--p-text-color, #111827);
}
.qr-add__hint {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
}
.qr-add__error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 0.5rem;
  padding: 1rem;
  text-align: center;
  color: #b91c1c;
}
.qr-add__error-text {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
  text-align: center;
}
.qr-add__footer {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--p-surface-200, #e5e7eb);
}
.qr-add__toast {
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
