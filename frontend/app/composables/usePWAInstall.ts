import type { ComputedRef } from 'vue'

/**
 * F12.6 PWA インストール制御 composable。
 *
 * `beforeinstallprompt` イベントをキャプチャしてユーザー操作によるインストールを可能にする。
 * iOS Safari は beforeinstallprompt をサポートしないため、UI 側で案内を出す用の判定フラグも返す。
 */

// navigator.standalone は iOS Safari 独自プロパティ。標準型に存在しないため拡張する。
interface NavigatorStandalone extends Navigator {
  standalone?: boolean
}

// Chromium 系の `beforeinstallprompt` イベント型（標準 DOM 型には未定義）。
interface BeforeInstallPromptEvent extends Event {
  readonly platforms: ReadonlyArray<string>
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>
  prompt: () => Promise<void>
}

const INSTALL_CHOICE_KEY = 'pwa-install-choice'
const DISMISS_SESSION_KEY = 'pwa-install-dismissed-session'

export function usePWAInstall() {
  const deferredPrompt = ref<BeforeInstallPromptEvent | null>(null)
  // sessionStorage 値は SSR 時に参照できないので ref で橋渡しする
  const dismissedThisSession = ref<boolean>(false)

  const canInstall: ComputedRef<boolean> = computed(() => deferredPrompt.value !== null)

  const isInstalled: ComputedRef<boolean> = computed(() => {
    if (typeof window === 'undefined') return false
    if (window.matchMedia?.('(display-mode: standalone)').matches) return true
    const nav = window.navigator as NavigatorStandalone
    return nav.standalone === true
  })

  const isIOS: ComputedRef<boolean> = computed(() => {
    if (typeof navigator === 'undefined') return false
    return /iPhone|iPad|iPod/i.test(navigator.userAgent)
  })

  const isIOSSafari: ComputedRef<boolean> = computed(() => {
    if (!isIOS.value) return false
    if (typeof navigator === 'undefined') return false
    const ua = navigator.userAgent
    return /Safari/i.test(ua) && !/CriOS|FxiOS/i.test(ua)
  })

  const isDismissedThisSession: ComputedRef<boolean> = computed(() => dismissedThisSession.value)

  function handleBeforeInstallPrompt(e: Event) {
    // ブラウザ標準のミニバーを抑止し、任意のタイミングで prompt() を呼べるようにする
    e.preventDefault()
    deferredPrompt.value = e as BeforeInstallPromptEvent
  }

  function handleAppInstalled() {
    deferredPrompt.value = null
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(INSTALL_CHOICE_KEY, 'accepted')
    }
  }

  async function promptInstall(): Promise<'accepted' | 'dismissed' | 'unavailable'> {
    if (!deferredPrompt.value) return 'unavailable'
    try {
      await deferredPrompt.value.prompt()
      const choice = await deferredPrompt.value.userChoice
      if (typeof localStorage !== 'undefined') {
        // outcome と意思決定タイムスタンプを両方残す（UI 側で再表示抑制に使う）
        localStorage.setItem(INSTALL_CHOICE_KEY, choice.outcome)
        localStorage.setItem(`${INSTALL_CHOICE_KEY}-at`, String(Date.now()))
      }
      deferredPrompt.value = null
      return choice.outcome
    } catch {
      deferredPrompt.value = null
      return 'unavailable'
    }
  }

  function dismissForNow(): void {
    if (typeof sessionStorage === 'undefined') return
    sessionStorage.setItem(DISMISS_SESSION_KEY, 'true')
    dismissedThisSession.value = true
  }

  onMounted(() => {
    if (typeof window === 'undefined') return
    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.addEventListener('appinstalled', handleAppInstalled)
    // マウント時点で既に dismiss 済みかを復元する
    if (typeof sessionStorage !== 'undefined') {
      dismissedThisSession.value = sessionStorage.getItem(DISMISS_SESSION_KEY) === 'true'
    }
  })

  onUnmounted(() => {
    if (typeof window === 'undefined') return
    window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.removeEventListener('appinstalled', handleAppInstalled)
  })

  return {
    canInstall,
    isInstalled,
    isIOS,
    isIOSSafari,
    isDismissedThisSession,
    promptInstall,
    dismissForNow,
  }
}
