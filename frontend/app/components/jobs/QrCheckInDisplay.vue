<script setup lang="ts">
import QRCode from 'qrcode'
import type { JobCheckInType, QrTokenResponse } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 QR チェックイン／アウト表示コンポーネント（Requester 側）。
 *
 * <p>Requester のデバイス上で QR コードを大きく表示し、Worker がカメラでスキャンする。
 * QR 読取失敗時のフォールバックとして shortCode（英数 6 桁）を強調表示する。</p>
 *
 * <p>トークンは expiresAt - 5 秒で自動再発行される（{@link useQrTokenApi#startAutoRotation}）。
 * コンポーネントは再発行された新トークンを表示し、期限までの残秒をプログレスバーで可視化する。</p>
 *
 * <p>セキュリティ面の注意としてスクリーンショット撮影警告を下部に常時表示する。</p>
 */

interface Props {
  /** 対象契約 ID。 */
  contractId: number
  /** IN（入場）か OUT（退場）か。 */
  type: JobCheckInType
}

const props = defineProps<Props>()

const { t } = useI18n()
const { error } = useNotification()
const qrTokenApi = useQrTokenApi()

// ============================================================
// 状態
// ============================================================

/** 現在表示中のトークン。未発行なら null。 */
const currentToken = ref<QrTokenResponse | null>(null)
/** QR コード（SVG 文字列）。 */
const qrSvg = ref<string>('')
/** 初回ロード中フラグ。 */
const loading = ref(true)
/** ローテーション停止ハンドル。 */
let stopRotation: (() => void) | null = null
/** 残秒カウントダウン用の setInterval ハンドル。 */
let countdownTimer: ReturnType<typeof setInterval> | null = null
/** 残秒数（プログレスバー表示用）。 */
const remainingSeconds = ref<number>(0)
/** 発行時 TTL（秒）。初回応答から算出して保持する。 */
const ttlSeconds = ref<number>(0)

// ============================================================
// QR コード生成
// ============================================================

/**
 * トークン文字列から SVG QR を生成する。
 *
 * <p>{@code qrcode} ライブラリの {@code toString(..., { type: 'svg' })} は
 * Promise を返すため、await で待ってから reactive に反映する。</p>
 */
async function renderQr(token: string) {
  try {
    qrSvg.value = await QRCode.toString(token, {
      type: 'svg',
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 512,
    })
  }
  catch (e) {
    qrSvg.value = ''
    error(t('jobmatching.qr.error.renderFailed'), String(e))
  }
}

// ============================================================
// ローテーション・カウントダウン
// ============================================================

function startCountdown() {
  if (countdownTimer !== null) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    if (!currentToken.value) {
      remainingSeconds.value = 0
      return
    }
    const expiresMs = new Date(currentToken.value.expiresAt).getTime()
    const nowMs = Date.now()
    remainingSeconds.value = Math.max(0, Math.ceil((expiresMs - nowMs) / 1000))
  }, 500)
}

function onNewToken(token: QrTokenResponse) {
  currentToken.value = token
  loading.value = false
  // 初回トークンから TTL を推定（残秒プログレスバーの満量計算用）。
  const issuedMs = new Date(token.issuedAt).getTime()
  const expiresMs = new Date(token.expiresAt).getTime()
  const ttl = Math.max(1, Math.round((expiresMs - issuedMs) / 1000))
  if (ttlSeconds.value === 0) ttlSeconds.value = ttl
  // JWT 文字列が null（/current 応答）の場合は QR を描けないため、その旨を示す。
  if (token.token) {
    void renderQr(token.token)
  }
  else {
    qrSvg.value = ''
  }
}

function onRotationError(e: unknown) {
  loading.value = false
  error(t('jobmatching.qr.error.issueFailed'), String(e))
}

// ============================================================
// プログレスバー計算
// ============================================================

/** 残秒の百分率（0〜100）。 */
const remainingPercent = computed(() => {
  if (ttlSeconds.value <= 0) return 0
  return Math.min(100, Math.max(0, (remainingSeconds.value / ttlSeconds.value) * 100))
})

/** 残秒が 10 秒以下になったら警告色に変える。 */
const progressSeverity = computed<'success' | 'warn' | 'danger'>(() => {
  if (remainingSeconds.value <= 5) return 'danger'
  if (remainingSeconds.value <= 10) return 'warn'
  return 'success'
})

// ============================================================
// ライフサイクル
// ============================================================

onMounted(() => {
  stopRotation = qrTokenApi.startAutoRotation(
    props.contractId,
    props.type,
    onNewToken,
    onRotationError,
  )
  startCountdown()
})

onBeforeUnmount(() => {
  if (stopRotation) stopRotation()
  if (countdownTimer !== null) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
})
</script>

<template>
  <div class="flex flex-col items-center gap-4">
    <!-- タイトル（IN / OUT） -->
    <h2 class="text-xl font-bold">
      {{ t(`jobmatching.qr.display.title.${type}`) }}
    </h2>

    <!-- QR コード本体（80vw 正方形、最大 480px） -->
    <div
      class="relative flex aspect-square w-[80vw] max-w-[480px] items-center justify-center rounded-xl border border-surface-300 bg-white p-4 dark:border-surface-600"
      role="img"
      :aria-label="t('jobmatching.qr.display.qrAriaLabel')"
    >
      <div
        v-if="loading"
        class="flex flex-col items-center gap-2"
      >
        <ProgressSpinner />
        <p class="text-sm text-surface-500">
          {{ t('jobmatching.qr.display.loading') }}
        </p>
      </div>
      <!-- qrcode ライブラリが生成する静的 SVG を描画する（外部入力を含まないため XSS リスクは無い）。 -->
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div
        v-else-if="qrSvg"
        class="h-full w-full"
        v-html="qrSvg"
      />
      <div
        v-else
        class="px-4 text-center text-sm text-surface-500"
      >
        {{ t('jobmatching.qr.display.unavailable') }}
      </div>
    </div>

    <!-- shortCode 強調表示 -->
    <div class="flex flex-col items-center gap-1">
      <p class="text-xs text-surface-500">
        {{ t('jobmatching.qr.display.shortCodeLabel') }}
      </p>
      <p
        class="select-all font-mono text-3xl font-bold tracking-[0.3em] text-primary-600 dark:text-primary-300"
        data-testid="qr-short-code"
      >
        {{ currentToken?.shortCode ?? '------' }}
      </p>
    </div>

    <!-- 残秒プログレスバー -->
    <div class="w-[80vw] max-w-[480px]">
      <div class="mb-1 flex justify-between text-xs text-surface-500">
        <span>{{ t('jobmatching.qr.display.remainingLabel') }}</span>
        <span data-testid="qr-remaining-seconds">
          {{ t('jobmatching.qr.display.remainingSeconds', { sec: remainingSeconds }) }}
        </span>
      </div>
      <div class="h-2 w-full overflow-hidden rounded-full bg-surface-200 dark:bg-surface-700">
        <div
          class="h-full transition-all duration-500"
          :class="{
            'bg-green-500': progressSeverity === 'success',
            'bg-amber-500': progressSeverity === 'warn',
            'bg-red-500': progressSeverity === 'danger',
          }"
          :style="{ width: `${remainingPercent}%` }"
        />
      </div>
    </div>

    <!-- スクショ警告 -->
    <div
      class="mt-2 w-[80vw] max-w-[480px] rounded-lg border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
      role="alert"
    >
      <p class="font-semibold">
        {{ t('jobmatching.qr.display.screenshotWarning.title') }}
      </p>
      <p class="mt-1">
        {{ t('jobmatching.qr.display.screenshotWarning.body') }}
      </p>
    </div>
  </div>
</template>
