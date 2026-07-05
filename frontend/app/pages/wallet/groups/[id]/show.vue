<script setup lang="ts">
import { useSwipe } from '@vueuse/core'
import GroupShowCardView from '~/components/wallet/group-show/GroupShowCardView.vue'
import GroupShowFooter from '~/components/wallet/group-show/GroupShowFooter.vue'
import GroupShowHeader from '~/components/wallet/group-show/GroupShowHeader.vue'
import GroupShowWarningModal from '~/components/wallet/group-show/GroupShowWarningModal.vue'
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
 *
 * <p>リファクタリング第11弾（2026-05-17）でロジックを以下に分割した（振る舞い変更なし）:</p>
 * <ul>
 *   <li>Wake Lock + nosleep + fullscreen → {@code composables/wallet-group-show/useWakeLockWithFallback.ts}</li>
 *   <li>WebAuthn 再認証 → {@code composables/wallet-group-show/useBiometricGate.ts}</li>
 *   <li>テンプレート（ヘッダ・中央カード・フッタ・警告モーダル）→ {@code components/wallet/group-show/*.vue}</li>
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

const groupId = computed(() => route.params.id as string)

// ─────────────────────────────────────────────
// 分割した composable（振る舞い変更なし）
// ─────────────────────────────────────────────

const { acquireWakeLock, releaseWakeLock, enterFullscreen, exitFullscreen } = useWakeLockWithFallback()
const { verifyBiometric } = useBiometricGate()

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
    //    ここが本質的な失敗の境界線。この後のステップが失敗しても loadError にしない。
    const result = await walletOffline.getGroupForPresentation(groupId.value)
    group.value = result.group
    cachedFromOffline.value = result.cachedFromOffline
    currentIndex.value = 0

    // 3. スクリーンキャプチャ警告（初回のみ）
    const acked = typeof localStorage !== 'undefined'
      && localStorage.getItem(SCREEN_CAPTURE_WARNING_KEY) === '1'
    showScreenCaptureWarning.value = !acked

    // 4. 初枚目の last_used_at を fire-and-forget で更新
    recordUsedForCurrent()
  }
  catch (e) {
    console.error('[wallet/groups/[id]/show] setup failed', e)
    loadError.value = t('wallet.presentation.load_failed')
  }
  finally {
    loading.value = false
  }

  // 5. Wake Lock 取得 + Fullscreen 要求（付加機能・best-effort）。
  //    グループ取得の成否に関係なく独立して実行し、失敗しても提示モードを継続する。
  //    onMounted 経由では requestFullscreen がジェスチャーコンテキスト外になるブラウザがあるが、
  //    その場合も catch で吸収する（useWakeLockWithFallback 内部実装で対処済み）。
  //    ここを setup() の try-catch 外に置くことで、Wake Lock/Fullscreen の失敗が
  //    グループ取得失敗（loadError）と誤って混同されるのを防ぐ。
  if (!loadError.value) {
    acquireWakeLock().catch((e) => {
      if (import.meta.dev) console.warn('[presentation] acquireWakeLock (outer) failed', e)
    })
    enterFullscreen().catch((e) => {
      if (import.meta.dev) console.warn('[presentation] enterFullscreen (outer) failed', e)
    })
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
    <GroupShowHeader
      :total="total"
      :page-indicator="pageIndicator"
      :provider-display-name="currentCard?.providerDisplayName ?? null"
      @close="closePresentation"
    />

    <!-- 中央: バーコード本体 -->
    <GroupShowCardView
      :loading="loading"
      :load-error="loadError"
      :biometric-failed="biometricFailed"
      :total="total"
      :current-card="currentCard"
      @reload="reload"
    />

    <!-- 下部ヒント + 進捗 + 再読み込み -->
    <GroupShowFooter
      v-if="!loading && !loadError && total > 0"
      :items="items"
      :current-index="currentIndex"
      :total="total"
      @reload="reload"
    />

    <!-- オフラインキャッシュ通知バナー -->
    <div v-if="cachedFromOffline" class="presentation__offline-banner" role="status">
      {{ t('wallet.offline.cached_notice') }}
    </div>

    <!-- スクリーンキャプチャ警告モーダル（初回のみ） -->
    <GroupShowWarningModal
      v-if="showScreenCaptureWarning"
      @dismiss="dismissWarning"
    />
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
</style>
