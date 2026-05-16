<script setup lang="ts">
import { useSwipe } from '@vueuse/core'
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'
import type { PointCardGroupDetail, PointCardGroupItem } from '~/types/pointCard'

/**
 * F18 個人ポイントカードウォレット — 提示モードページ（設計書 §3 UC-3/UC-4 / §8.4 / §9）。
 *
 * <p>レジでバーコードを順次提示する全画面 UI。1 グループ内のカードを横スワイプ
 * または矢印キーで切り替え、画面の明るさ維持のため Wake Lock API を取得する。
 * Wake Lock 未対応ブラウザは nosleep.js（無音動画ループ）にフォールバックする。</p>
 *
 * <ul>
 *   <li>ライフサイクル順序: (1) 設定取得 → 必要なら WebAuthn 再認証
 *       (2) {@code useWalletOffline.getGroupForPresentation} でグループ取得
 *       (3) スクリーンキャプチャ警告（初回のみ） (4) Wake Lock 取得
 *       (5) 全画面要求 (6) 初枚目を表示</li>
 *   <li>各カード表示時に {@code useWalletApi.recordUsed} を fire-and-forget で呼び
 *       バックエンドの {@code last_used_at} を更新する（レート 600/h・監査ログ非記録）。</li>
 *   <li>初回オープン時のみスクリーンキャプチャ警告モーダルを表示する
 *       （localStorage で既読フラグ管理）。</li>
 *   <li>onBeforeUnmount で Wake Lock 解放・nosleep 停止・fullscreen 解除を確実に行う。</li>
 * </ul>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const walletApi = useWalletApi()
const walletOffline = useWalletOffline()
const authApi = useAuthApi()

const groupId = computed(() => route.params.id as string)

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

const loading = ref(true)
const loadError = ref<string | null>(null)
const group = ref<PointCardGroupDetail | null>(null)
const cachedFromOffline = ref(false)
const currentIndex = ref(0)
const showScreenCaptureWarning = ref(false)
const biometricFailed = ref(false)

const SCREEN_CAPTURE_WARNING_KEY = 'wallet:screen_capture_warning_acked'

// ─────────────────────────────────────────────
// Wake Lock + nosleep フォールバック
// ─────────────────────────────────────────────

// WakeLockSentinel 型がない環境向けの最小定義（lib.dom の WakeLockSentinel に準拠）
interface WakeLockSentinelLike {
  release: () => Promise<void>
  addEventListener: (type: 'release', listener: () => void) => void
}

let wakeLock: WakeLockSentinelLike | null = null
// nosleep.js の型は default export のインスタンス。動的 import で取得する
let noSleep: { enable: () => Promise<void>; disable: () => void } | null = null

async function acquireWakeLock(): Promise<void> {
  // WakeLock API が使えるなら最優先で使う（ネイティブ・低オーバーヘッド）
  const nav = navigator as Navigator & {
    wakeLock?: { request: (type: 'screen') => Promise<WakeLockSentinelLike> }
  }
  if (nav.wakeLock?.request) {
    try {
      const sentinel = await nav.wakeLock.request('screen')
      wakeLock = sentinel
      sentinel.addEventListener('release', () => {
        wakeLock = null
      })
      return
    }
    catch (e) {
      // ユーザーの設定で拒否される / バッテリーセーブモード等で失敗するケースは
      // 黙ってフォールバックに移行する（症状を隠しているわけではなく仕様通り）
      if (import.meta.dev) console.warn('[presentation] wakeLock.request failed, falling back to nosleep.js', e)
    }
  }
  // フォールバック: nosleep.js（無音動画ループで画面を起こし続ける）
  try {
    const mod = await import('nosleep.js')
    const NoSleepCtor = mod.default
    const instance = new NoSleepCtor()
    await instance.enable()
    noSleep = instance
  }
  catch (e) {
    // ここまで来たら諦める。提示モード自体は継続可能（ユーザー操作で画面オン維持）
    if (import.meta.dev) console.warn('[presentation] nosleep.js fallback failed', e)
  }
}

function releaseWakeLock(): void {
  if (wakeLock) {
    // release は Promise を返すが unmount 経路では待たない（fire-and-forget）
    wakeLock.release().catch((e) => {
      if (import.meta.dev) console.warn('[presentation] wakeLock.release failed', e)
    })
    wakeLock = null
  }
  if (noSleep) {
    noSleep.disable()
    noSleep = null
  }
}

// ─────────────────────────────────────────────
// Fullscreen
// ─────────────────────────────────────────────

async function enterFullscreen(): Promise<void> {
  const el = document.documentElement
  if (!el.requestFullscreen) return
  try {
    await el.requestFullscreen()
  }
  catch (e) {
    // iOS Safari など fullscreen 非対応 / ユーザー拒否時は無視（提示モード継続）
    if (import.meta.dev) console.warn('[presentation] requestFullscreen failed', e)
  }
}

async function exitFullscreen(): Promise<void> {
  if (!document.fullscreenElement) return
  try {
    await document.exitFullscreen()
  }
  catch (e) {
    if (import.meta.dev) console.warn('[presentation] exitFullscreen failed', e)
  }
}

// ─────────────────────────────────────────────
// 生体認証ゲート
// ─────────────────────────────────────────────

// base64url ⇄ ArrayBuffer 変換ユーティリティ（依存追加を避けて手書き）
function b64urlToBuf(s: string): ArrayBuffer {
  const pad = '='.repeat((4 - (s.length % 4)) % 4)
  const b64 = (s + pad).replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64)
  const buf = new ArrayBuffer(bin.length)
  const view = new Uint8Array(buf)
  for (let i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i)
  return buf
}

function bufToB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let bin = ''
  for (let i = 0; i < bytes.byteLength; i++) bin += String.fromCharCode(bytes[i]!)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/**
 * authenticatorData (CBOR デコード不要、固定オフセット) から signCount を抽出する。
 *
 * <p>WebAuthn 仕様 §6.1: authenticatorData の構造
 * <pre>
 *   rpIdHash (32) | flags (1) | signCount (4, big-endian) | ...
 * </pre>
 * signCount は バイト 33-36 (0-indexed) の 32bit big-endian unsigned。
 */
function extractSignCount(authenticatorData: ArrayBuffer): number {
  const view = new DataView(authenticatorData)
  // 32 (rpIdHash) + 1 (flags) = 33 から 4 バイトが signCount
  return view.getUint32(33, false)
}

/**
 * 設定 `requireBiometricOnShow=true` のときに呼ぶ。サーバー側 5 分 TTL 検証を含む 3 段フロー。
 *
 * <ol>
 *   <li>{@code POST /reauthenticate-begin} でチャレンジ取得</li>
 *   <li>{@code navigator.credentials.get} で署名取得（OS 生体認証 / PIN ダイアログ）</li>
 *   <li>{@code POST /reauthenticate-complete} でサーバー検証 → 「再認証済みフラグ」が
 *       Valkey に 5 分 TTL で記録される</li>
 * </ol>
 *
 * <p>completeReauthenticate は AT/RT を再発行しない。フラグは提示モード起動 API が
 * 1 回限りで消費する（POINT_CARD_009 再生攻撃防止）。
 *
 * @returns 成功で true / ユーザーキャンセル or 失敗で false
 */
async function verifyBiometric(): Promise<boolean> {
  // WebAuthn API 自体が無い環境（古いブラウザ・SSR）ではスキップして警告のみとする
  if (typeof window === 'undefined' || !window.PublicKeyCredential || !navigator.credentials) {
    return false
  }
  try {
    // 1. begin: 再認証用チャレンジ取得（既存ログインフローとは別エンドポイント）
    const beginRes = await authApi.beginWebAuthnReauthenticate()
    const begin = beginRes.data

    const publicKey: PublicKeyCredentialRequestOptions = {
      challenge: b64urlToBuf(begin.challenge),
      rpId: begin.rpId,
      timeout: begin.timeout,
      userVerification: 'preferred',
      allowCredentials: begin.allowCredentials.map((id) => ({
        type: 'public-key' as const,
        id: b64urlToBuf(id),
      })),
    }

    // 2. navigator.credentials.get で署名取得
    const cred = await navigator.credentials.get({ publicKey })
    if (!cred) return false

    // PublicKeyCredential を取り出してサーバー検証用に Base64URL 化
    const pkCred = cred as PublicKeyCredential
    const assertion = pkCred.response as AuthenticatorAssertionResponse

    const credentialId = bufToB64url(pkCred.rawId)
    const authenticatorDataB64 = bufToB64url(assertion.authenticatorData)
    const clientDataJsonB64 = bufToB64url(assertion.clientDataJSON)
    const signatureB64 = bufToB64url(assertion.signature)
    const signCount = extractSignCount(assertion.authenticatorData)

    // 3. complete: サーバー検証 → 再認証済みフラグが Valkey に書き込まれる
    await authApi.completeWebAuthnReauthenticate({
      credentialId,
      authenticatorData: authenticatorDataB64,
      clientDataJson: clientDataJsonB64,
      signature: signatureB64,
      signCount,
    })
    return true
  }
  catch (e) {
    // ユーザーキャンセル / タイムアウト / authenticator なし / サーバー検証失敗
    if (import.meta.dev) console.warn('[presentation] biometric verification failed', e)
    return false
  }
}

// ─────────────────────────────────────────────
// ライフサイクル
// ─────────────────────────────────────────────

async function setup(): Promise<void> {
  loading.value = true
  loadError.value = null
  biometricFailed.value = false

  try {
    // 1. ユーザー設定の確認 → 必要なら WebAuthn 再認証
    let settings
    try {
      settings = await walletApi.getSettings()
    }
    catch (e) {
      // 設定取得失敗時はオフラインでも提示できるよう続行（require_biometric は false 扱い）
      if (import.meta.dev) console.warn('[presentation] getSettings failed', e)
      settings = null
    }
    if (settings?.requireBiometricOnShow) {
      const ok = await verifyBiometric()
      if (!ok) {
        biometricFailed.value = true
        loadError.value = t('wallet.presentation.biometric_failed')
        loading.value = false
        // 編集画面へ戻すまでの猶予として 1.5 秒だけメッセージを表示
        window.setTimeout(() => {
          router.push(`/wallet/groups/${groupId.value}`)
        }, 1500)
        return
      }
    }

    // 2. グループ取得（オンライン優先、失敗時 IndexedDB 復元）。
    //    成功時はサーバーで POINT_CARD_VIEWED 監査ログが記録される。
    const result = await walletOffline.getGroupForPresentation(groupId.value)
    group.value = result.group
    cachedFromOffline.value = result.cachedFromOffline
    currentIndex.value = 0

    // 3. スクリーンキャプチャ警告（初回のみ）
    const acked = typeof localStorage !== 'undefined'
      && localStorage.getItem(SCREEN_CAPTURE_WARNING_KEY) === '1'
    showScreenCaptureWarning.value = !acked

    // 4. Wake Lock 取得 + Fullscreen 要求
    //    警告モーダル表示中も裏で取得を進めておく（ユーザー操作前に確保）。
    await acquireWakeLock()
    await enterFullscreen()

    // 5. 初枚目の last_used_at を fire-and-forget で更新
    recordUsedForCurrent()
  }
  catch (e) {
    console.error('[wallet/groups/[id]/show] setup failed', e)
    loadError.value = t('wallet.presentation.load_failed')
  }
  finally {
    loading.value = false
  }
}

function dismissWarning(): void {
  showScreenCaptureWarning.value = false
  if (typeof localStorage !== 'undefined') {
    try {
      localStorage.setItem(SCREEN_CAPTURE_WARNING_KEY, '1')
    }
    catch (e) {
      // QuotaExceeded 等：警告は表示済みなので運用上は無害
      if (import.meta.dev) console.warn('[presentation] localStorage.setItem failed', e)
    }
  }
}

// ─────────────────────────────────────────────
// カード切替
// ─────────────────────────────────────────────

const items = computed<PointCardGroupItem[]>(() => group.value?.items ?? [])
const currentCard = computed<PointCardGroupItem | null>(
  () => items.value[currentIndex.value] ?? null,
)
const total = computed(() => items.value.length)
const pageIndicator = computed(() =>
  t('wallet.presentation.page_indicator', {
    current: total.value === 0 ? 0 : currentIndex.value + 1,
    total: total.value,
  }),
)
const brandColor = computed(() => currentCard.value?.providerBrandColor ?? null)

function recordUsedForCurrent(): void {
  const card = currentCard.value
  if (!card) return
  // fire-and-forget。サーバー側に 600/h レート制限がある（仕様）ため再試行不要
  walletApi.recordUsed(card.cardId).catch((e) => {
    if (import.meta.dev) console.warn('[presentation] recordUsed failed', e)
  })
}

function nextCard(): void {
  if (items.value.length === 0) return
  if (currentIndex.value >= items.value.length - 1) return
  currentIndex.value += 1
  recordUsedForCurrent()
}

function previousCard(): void {
  if (currentIndex.value <= 0) return
  currentIndex.value -= 1
  recordUsedForCurrent()
}

async function closePresentation(): Promise<void> {
  await router.push(`/wallet/groups/${groupId.value}`)
}

// ─────────────────────────────────────────────
// スワイプ + キーボード
// ─────────────────────────────────────────────

const swipeTarget = ref<HTMLElement | null>(null)
useSwipe(swipeTarget, {
  threshold: 50,
  onSwipeEnd(_e, direction) {
    if (direction === 'left') nextCard()
    else if (direction === 'right') previousCard()
  },
})

function onKeyDown(e: KeyboardEvent): void {
  if (showScreenCaptureWarning.value) return
  if (e.key === 'ArrowLeft') {
    e.preventDefault()
    previousCard()
  }
  else if (e.key === 'ArrowRight') {
    e.preventDefault()
    nextCard()
  }
  else if (e.key === 'Escape') {
    e.preventDefault()
    closePresentation()
  }
}

// ─────────────────────────────────────────────
// 再読み込み（バーコードが読めなかった時のフォールバック）
// ─────────────────────────────────────────────

async function reload(): Promise<void> {
  // setup を最初からやり直すのではなく、グループ情報のみ取り直す（生体認証は再要求しない）
  loadError.value = null
  try {
    const result = await walletOffline.getGroupForPresentation(groupId.value)
    group.value = result.group
    cachedFromOffline.value = result.cachedFromOffline
    // インデックスは現在値を維持しつつ範囲外なら 0 に戻す
    if (currentIndex.value >= result.group.items.length) {
      currentIndex.value = 0
    }
    recordUsedForCurrent()
  }
  catch (e) {
    console.error('[wallet/groups/[id]/show] reload failed', e)
    loadError.value = t('wallet.presentation.load_failed')
  }
}

// ─────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────

onMounted(() => {
  setup()
  window.addEventListener('keydown', onKeyDown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  releaseWakeLock()
  exitFullscreen()
})
</script>

<template>
  <div
    ref="swipeTarget"
    class="presentation"
    :style="brandColor ? { background: brandColor } : undefined"
  >
    <!-- 上部ヘッダ -->
    <header class="presentation__header">
      <button
        type="button"
        class="presentation__close"
        :aria-label="t('wallet.presentation.close')"
        @click="closePresentation"
      >
        ×
      </button>
      <div v-if="total > 0" class="presentation__indicator">
        {{ pageIndicator }}
      </div>
      <div v-if="currentCard?.providerDisplayName" class="presentation__provider">
        {{ currentCard.providerDisplayName }}
      </div>
      <div v-else class="presentation__provider" aria-hidden="true">&nbsp;</div>
    </header>

    <!-- 中央: バーコード本体 -->
    <main class="presentation__main">
      <p v-if="loading" class="presentation__loading">…</p>

      <div v-else-if="loadError" class="presentation__error" role="alert">
        <p>{{ loadError }}</p>
        <button
          v-if="!biometricFailed"
          type="button"
          class="presentation__btn"
          @click="reload"
        >
          ↻ {{ t('wallet.presentation.reload') }}
        </button>
      </div>

      <p v-else-if="total === 0" class="presentation__empty">
        {{ t('wallet.presentation.no_cards') }}
      </p>

      <div v-else-if="currentCard" class="presentation__card">
        <img
          v-if="currentCard.providerLogoUrl"
          :src="currentCard.providerLogoUrl"
          :alt="currentCard.providerDisplayName ?? ''"
          class="presentation__logo"
        >
        <BarcodePreview
          :value="currentCard.barcodeValue"
          :format="currentCard.barcodeFormat"
          size="large"
        />
        <h2 class="presentation__name">{{ currentCard.displayName }}</h2>
        <p v-if="currentCard.last4" class="presentation__last4">
          ●●●● {{ currentCard.last4 }}
        </p>
      </div>
    </main>

    <!-- 下部ヒント + 進捗 + 再読み込み -->
    <footer v-if="!loading && !loadError && total > 0" class="presentation__footer">
      <p class="presentation__hint">
        {{ t('wallet.presentation.hint_swipe') }}
      </p>
      <p class="presentation__hint presentation__hint--muted">
        {{ t('wallet.presentation.hint_brightness') }}
      </p>
      <div v-if="total > 1" class="presentation__progress">
        <span
          v-for="(_, idx) in items"
          :key="idx"
          class="presentation__dot"
          :class="{ 'presentation__dot--active': idx === currentIndex }"
        />
      </div>
      <button type="button" class="presentation__btn" @click="reload">
        ↻ {{ t('wallet.presentation.reload') }}
      </button>
    </footer>

    <!-- オフラインキャッシュ通知バナー -->
    <div v-if="cachedFromOffline" class="presentation__offline-banner" role="status">
      {{ t('wallet.offline.cached_notice') }}
    </div>

    <!-- スクリーンキャプチャ警告モーダル（初回のみ） -->
    <div
      v-if="showScreenCaptureWarning"
      class="presentation__modal-backdrop"
      role="dialog"
      aria-modal="true"
      :aria-label="t('wallet.presentation.warning_title')"
    >
      <div class="presentation__modal">
        <h2 class="presentation__modal-title">
          {{ t('wallet.presentation.warning_title') }}
        </h2>
        <p class="presentation__modal-body">
          {{ t('wallet.presentation.warning_body') }}
        </p>
        <button
          type="button"
          class="presentation__btn presentation__btn--primary"
          @click="dismissWarning"
        >
          {{ t('wallet.presentation.warning_ack') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.presentation {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #000;
  color: #fff;
  z-index: 100;
  /* swipe 中の選択を防ぐ */
  user-select: none;
  -webkit-user-select: none;
  touch-action: pan-y;
}
.presentation__header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  background: rgba(0, 0, 0, 0.35);
}
.presentation__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  border: none;
  cursor: pointer;
  font-size: 1.5rem;
  line-height: 1;
}
.presentation__indicator {
  flex: 1;
  text-align: center;
  font-weight: 600;
  font-size: 0.9375rem;
  letter-spacing: 0.05em;
}
.presentation__provider {
  font-size: 0.875rem;
  font-weight: 600;
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}
.presentation__main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  overflow: hidden;
}
.presentation__card {
  background: #fff;
  color: #111;
  border-radius: 1rem;
  padding: 1.5rem 1.25rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.875rem;
  max-width: 96%;
  width: 100%;
  max-width: 520px;
}
.presentation__logo {
  height: 32px;
  max-width: 60%;
  object-fit: contain;
}
.presentation__name {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  text-align: center;
}
.presentation__last4 {
  font-size: 2rem;
  font-weight: 700;
  letter-spacing: 0.1em;
  margin: 0;
}
.presentation__loading,
.presentation__empty,
.presentation__error {
  text-align: center;
  font-size: 1rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
}
.presentation__footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem 1.25rem;
  background: rgba(0, 0, 0, 0.35);
}
.presentation__hint {
  margin: 0;
  font-size: 0.875rem;
  opacity: 0.85;
}
.presentation__hint--muted {
  font-size: 0.75rem;
  opacity: 0.6;
}
.presentation__progress {
  display: flex;
  gap: 0.375rem;
}
.presentation__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.35);
}
.presentation__dot--active {
  background: #fff;
  transform: scale(1.3);
}
.presentation__btn {
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  border: 1px solid rgba(255, 255, 255, 0.4);
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  cursor: pointer;
  font-weight: 600;
}
.presentation__btn--primary {
  background: #fff;
  color: #111;
  border-color: transparent;
}
.presentation__offline-banner {
  position: absolute;
  top: 64px;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.375rem 0.75rem;
  background: rgba(255, 255, 255, 0.85);
  color: #111;
  border-radius: 999px;
  font-size: 0.8125rem;
  font-weight: 600;
}
.presentation__modal-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  z-index: 10;
}
.presentation__modal {
  background: #fff;
  color: #111;
  border-radius: 0.75rem;
  padding: 1.5rem;
  max-width: 480px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  text-align: center;
}
.presentation__modal-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 700;
}
.presentation__modal-body {
  margin: 0;
  font-size: 0.9375rem;
  line-height: 1.5;
}
</style>
