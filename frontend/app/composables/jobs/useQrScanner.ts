/**
 * F13.1 Phase 13.1.2 QR スキャナ composable。
 *
 * <p>カメラ映像を起動し、フレームごとに QR を検出する。優先順位は以下:</p>
 * <ol>
 *   <li>{@code BarcodeDetector} API（Chrome on Android / 最新版 Chromium）</li>
 *   <li>{@code @zxing/browser} への動的 import フォールバック</li>
 * </ol>
 *
 * <p>成功時は {@code Vibration API 50ms} を鳴らし、{@code AudioContext} で短いビープを再生する。
 * これらは実装環境（非対応ブラウザ、テスト環境）では無視される（throw しない）。</p>
 */

/** 検出結果。 */
export interface QrScanResult {
  /** デコードされた文字列。 */
  text: string
  /** 検出器種別（監査・ロギング用）。 */
  detector: 'BarcodeDetector' | 'zxing'
  /** 検出時刻（Date.now()）。 */
  detectedAt: number
}

/** スキャナの状態。 */
export type ScannerState = 'idle' | 'starting' | 'running' | 'stopped' | 'error'

interface StartOptions {
  /** 映像を流す video 要素（呼び出し側で ref 経由渡す）。 */
  videoEl: HTMLVideoElement
  /** 検出時のコールバック。 */
  onScanned: (result: QrScanResult) => void
  /** エラー時のコールバック（権限拒否等）。 */
  onError?: (err: unknown) => void
  /**
   * 検出ポーリング間隔（ms）。{@code BarcodeDetector} 経路のみ使用する。
   * デフォルト 250ms（秒 4 回）。
   */
  detectIntervalMs?: number
}

// ============================================================
// BarcodeDetector 型（TS 標準定義が不足するため最小限）
// ============================================================

interface BarcodeDetection {
  rawValue: string
  format: string
}

interface BarcodeDetectorInstance {
  detect: (source: CanvasImageSource) => Promise<BarcodeDetection[]>
}

interface BarcodeDetectorCtor {
  new (options?: { formats?: string[] }): BarcodeDetectorInstance
  getSupportedFormats?: () => Promise<string[]>
}

function getBarcodeDetectorCtor(): BarcodeDetectorCtor | null {
  if (typeof window === 'undefined') return null
  const ctor = (window as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector
  return ctor ?? null
}

// ============================================================
// 副作用: バイブ・ビープ
// ============================================================

/** Vibration API を安全に叩く（非対応環境では noop）。 */
export function pulseVibration(ms = 50): void {
  if (typeof navigator === 'undefined') return
  const n = navigator as Navigator & { vibrate?: (p: number | number[]) => boolean }
  if (typeof n.vibrate === 'function') {
    try { n.vibrate(ms) } catch { /* noop */ }
  }
}

/** WebAudio で短いビープを再生する（非対応環境では noop）。 */
export function playBeep(durationMs = 120, frequency = 880): void {
  if (typeof window === 'undefined') return
  type WindowWithAudio = Window & {
    AudioContext?: typeof AudioContext
    webkitAudioContext?: typeof AudioContext
  }
  const w = window as WindowWithAudio
  const Ctor = w.AudioContext ?? w.webkitAudioContext
  if (!Ctor) return
  try {
    const ctx = new Ctor()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = frequency
    gain.gain.value = 0.08
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start()
    setTimeout(() => {
      try {
        osc.stop()
        void ctx.close()
      }
      catch { /* noop */ }
    }, durationMs)
  }
  catch {
    /* noop — 再生失敗は致命的ではない */
  }
}

// ============================================================
// 本体
// ============================================================

export function useQrScanner() {
  const state = ref<ScannerState>('idle')
  const errorMessage = ref<string | null>(null)

  let stream: MediaStream | null = null
  let detectTimer: ReturnType<typeof setInterval> | null = null
  let zxingControls: { stop: () => void } | null = null

  /** カメラを起動し、検出ループを開始する。 */
  async function start(opts: StartOptions): Promise<void> {
    if (state.value === 'running' || state.value === 'starting') return
    state.value = 'starting'
    errorMessage.value = null

    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' },
        audio: false,
      })
    }
    catch (e) {
      state.value = 'error'
      errorMessage.value = String(e)
      opts.onError?.(e)
      throw e
    }

    opts.videoEl.srcObject = stream
    opts.videoEl.playsInline = true
    opts.videoEl.muted = true
    try {
      await opts.videoEl.play()
    }
    catch { /* iOS でユーザーイベント起因でないと play が拒否されるケースのみ。呼び出し側でボタン押下後に叩く想定なので通常は通る。 */ }

    const ctor = getBarcodeDetectorCtor()
    if (ctor) {
      await startWithBarcodeDetector(ctor, opts)
      state.value = 'running'
      return
    }

    // フォールバック: @zxing/browser を動的 import
    try {
      const { BrowserMultiFormatReader } = await import('@zxing/browser')
      const reader = new BrowserMultiFormatReader()
      zxingControls = await reader.decodeFromStream(
        stream,
        opts.videoEl,
        (result, err) => {
          if (result) {
            const text = result.getText()
            pulseVibration()
            playBeep()
            opts.onScanned({
              text,
              detector: 'zxing',
              detectedAt: Date.now(),
            })
          }
          // err は大半が NotFoundException（検出未検出）。throw しないのが zxing の流儀。
          if (err && err.name !== 'NotFoundException') {
            opts.onError?.(err)
          }
        },
      )
      state.value = 'running'
    }
    catch (e) {
      state.value = 'error'
      errorMessage.value = String(e)
      opts.onError?.(e)
      stopStream()
      throw e
    }
  }

  async function startWithBarcodeDetector(ctor: BarcodeDetectorCtor, opts: StartOptions) {
    const detector = new ctor({ formats: ['qr_code'] })
    const interval = opts.detectIntervalMs ?? 250
    detectTimer = setInterval(async () => {
      if (!opts.videoEl.videoWidth) return
      try {
        const results = await detector.detect(opts.videoEl)
        if (results.length > 0) {
          pulseVibration()
          playBeep()
          opts.onScanned({
            text: results[0]!.rawValue,
            detector: 'BarcodeDetector',
            detectedAt: Date.now(),
          })
        }
      }
      catch (e) {
        // フレーム取得の一時エラーは無視（ログのみ）。
        opts.onError?.(e)
      }
    }, interval)
  }

  /** 検出ループとカメラストリームを停止する。 */
  function stop(): void {
    if (detectTimer !== null) {
      clearInterval(detectTimer)
      detectTimer = null
    }
    if (zxingControls) {
      try { zxingControls.stop() } catch { /* noop */ }
      zxingControls = null
    }
    stopStream()
    state.value = 'stopped'
  }

  function stopStream() {
    if (stream) {
      stream.getTracks().forEach((t) => {
        try { t.stop() } catch { /* noop */ }
      })
      stream = null
    }
  }

  onBeforeUnmount(() => {
    if (state.value === 'running') stop()
  })

  return {
    state: readonly(state),
    errorMessage: readonly(errorMessage),
    start,
    stop,
  }
}
