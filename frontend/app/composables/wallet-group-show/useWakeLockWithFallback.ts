/**
 * F18 個人ポイントカードウォレット — 提示モードの画面常時点灯制御。
 *
 * <p>Wake Lock API を最優先で取得し、未対応 / reject 時は nosleep.js
 * （無音動画ループ）にフォールバックする。fullscreen 要求もここに集約する。</p>
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。
 * ロジック・テレメトリ送信・呼び出しタイミングはすべて元実装と完全同一であり、
 * 振る舞いの変更は一切含まれない。</p>
 */

// WakeLockSentinel 型がない環境向けの最小定義（lib.dom の WakeLockSentinel に準拠）
interface WakeLockSentinelLike {
  release: () => Promise<void>
  addEventListener: (type: 'release', listener: () => void) => void
}

interface NoSleepInstanceLike {
  enable: () => Promise<void>
  disable: () => void
}

export interface UseWakeLockWithFallback {
  acquireWakeLock: () => Promise<void>
  releaseWakeLock: () => void
  enterFullscreen: () => Promise<void>
  exitFullscreen: () => Promise<void>
}

export function useWakeLockWithFallback(): UseWakeLockWithFallback {
  const { captureQuiet } = useErrorReport()

  let wakeLock: WakeLockSentinelLike | null = null
  // nosleep.js の型は default export のインスタンス。動的 import で取得する
  let noSleep: NoSleepInstanceLike | null = null

  async function acquireWakeLock(): Promise<void> {
    // WakeLock API が使えるなら最優先で使う（ネイティブ・低オーバーヘッド）
    const nav = navigator as Navigator & {
      wakeLock?: { request: (type: 'screen') => Promise<WakeLockSentinelLike> }
    }
    if (!nav.wakeLock?.request) {
      // 1) Wake Lock API 自体が未サポート（旧 Safari など）
      captureQuiet(
        new Error('Wake Lock API not supported in this browser'),
        { context: 'wake_lock_unsupported' },
      )
      await fallbackToNoSleep()
      return
    }
    try {
      const sentinel = await nav.wakeLock.request('screen')
      wakeLock = sentinel
      sentinel.addEventListener('release', () => {
        wakeLock = null
      })
      return
    }
    catch (e) {
      // 2) Wake Lock API は存在するが reject された
      //    （iOS Safari Low Power Mode / バッテリー残量低下 / ユーザー設定拒否など）
      if (import.meta.dev) console.warn('[presentation] wakeLock.request failed, falling back to nosleep.js', e)
      const name = e instanceof Error ? e.name : 'unknown'
      const msg = e instanceof Error ? e.message : String(e)
      captureQuiet(
        new Error(`Wake Lock request failed [${name}]: ${msg}`),
        { context: 'wake_lock_request_failed' },
      )
      await fallbackToNoSleep()
    }
  }

  /**
   * Wake Lock API が使えなかったときの最終手段。
   * nosleep.js（無音動画ループで画面を起こし続ける）にフォールバックする。
   *
   * <p>成功・失敗のいずれもテレメトリを送信する。失敗時（`wake_lock_nosleep_failed`）は
   * 致命傷（顧客提示中に画面が落ちる可能性）なので運用部隊が緊急調査する。</p>
   */
  async function fallbackToNoSleep(): Promise<void> {
    try {
      const mod = await import('nosleep.js')
      const NoSleepCtor = mod.default
      const instance = new NoSleepCtor()
      await instance.enable()
      noSleep = instance
      // 3) フォールバック成功（一定割合発生する想定。正常系の派生）
      captureQuiet(
        new Error('Fell back to nosleep.js successfully'),
        { context: 'wake_lock_nosleep_fallback_ok' },
      )
    }
    catch (e) {
      // 4) フォールバックも失敗（致命傷）。提示モード自体は継続可能だが画面が暗転しうる
      if (import.meta.dev) console.warn('[presentation] nosleep.js fallback failed', e)
      const name = e instanceof Error ? e.name : 'unknown'
      const msg = e instanceof Error ? e.message : String(e)
      captureQuiet(
        new Error(`nosleep.js fallback also failed [${name}]: ${msg}`),
        { context: 'wake_lock_nosleep_failed' },
      )
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

  return {
    acquireWakeLock,
    releaseWakeLock,
    enterFullscreen,
    exitFullscreen,
  }
}
