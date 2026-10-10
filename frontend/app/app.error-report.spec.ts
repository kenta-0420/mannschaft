import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import App from '~/app.vue'
import DefaultLayout from '~/layouts/default.vue'
import AuthLayout from '~/layouts/auth.vue'
import ErrorReportModal from '~/components/ErrorReportModal.vue'
import type { ErrorReportState } from '~/composables/useErrorReport'

mockNuxtImport('useAuthStore', () => () => ({
  isAuthenticated: false,
  accessToken: null,
  currentUser: null,
  loadFromStorage: vi.fn(),
}))
mockNuxtImport('useInboxStore', () => () => ({ fetchSummary: vi.fn() }))
mockNuxtImport('useUserNotificationSocket', () => () => ({ start: vi.fn(), stop: vi.fn() }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({
  isActingAs: false,
  activeChild: null,
  endSwitch: vi.fn(),
}))
mockNuxtImport('useGuardianshipApi', () => () => ({ endSwitch: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn() }))

// CMP-260920-1042: 単体部品だけではappとlayoutの二重描画を検出できない。
// 報告UI自体は実コンポーネントを描画し、周辺のページ・シェルだけをスタブ化する。
// happy-domのクリックは物理的な遮蔽を証明しないため、本番ブラウザでの確認も必要。
describe('アプリ全体のエラー報告UI', () => {
  let captureClock = Date.UTC(2030, 0, 1, 3)
  let cleanup: (() => void) | undefined

  beforeEach(() => {
    captureClock += 60_001
    vi.spyOn(Date, 'now').mockReturnValue(captureClock)
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({}))
    useState<ErrorReportState>('errorReport').value = {
      visible: false,
      expanded: false,
      submitting: false,
      submitted: false,
      commentSent: false,
      errorMessage: '',
      stackTrace: '',
      pageUrl: '',
      userAgent: '',
      requestId: '',
      context: '',
    }
    document.body.innerHTML = ''
  })

  afterEach(() => {
    cleanup?.()
    cleanup = undefined
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it.each([
    ['default', DefaultLayout],
    ['auth', AuthLayout],
  ] as const)('%s layoutでも報告は単一バッジから利用者が展開する', async (_name, layout) => {
    const resume = vi.fn()
    const Page = defineComponent({
      setup: () => () => h('button', { 'data-testid': 'resume', onClick: resume }, '操作を続ける'),
    })
    const wrapper = await mountSuspended(App, {
      attachTo: document.body,
      global: {
        stubs: {
          NuxtLayout: layout,
          NuxtPage: Page,
          ClientOnly: { template: '<slot />' },
          AppShell: { template: '<main><slot /></main>' },
          Card: { template: '<div><slot name="content" /><slot /></div>' },
          ActiveIncidentBanner: true,
          NavigationLoading: true,
          Toast: true,
          ConfirmDialog: true,
          DynamicDialog: true,
          PaywallModal: true,
          IosInstallGuideModal: true,
          QuickMemoCaptureModal: true,
          FeedbackSubmitModal: true,
          AdminImpersonationBanner: true,
          OfflineStatusBanner: true,
        },
      },
    })

    cleanup = () => wrapper.unmount()

    const { capture } = useErrorReport()
    capture(new Error('HTTP 502 background poll'))
    await nextTick()

    const t = wrapper.vm.$t.bind(wrapper.vm)
    const badges = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button[aria-label]'))
      .filter((button) => button.getAttribute('aria-label') === t('error_report.widget.badge_aria_label'))
    expect(badges).toHaveLength(1)
    expect(document.body.querySelector('.w-80')).toBeNull()
    expect(document.body.querySelector('.p-dialog-mask')).toBeNull()
    // 旧Modalは開発モードでは非表示でも本番でvisibleに反応するため、mount自体を許さない。
    expect(wrapper.findComponent(ErrorReportModal).exists()).toBe(false)
    await wrapper.get('[data-testid="resume"]').trigger('click')
    expect(resume).toHaveBeenCalledOnce()

    badges[0]!.click()
    await nextTick()
    expect(document.body.querySelectorAll('.w-80')).toHaveLength(1)
    expect(document.body.querySelectorAll('textarea')).toHaveLength(1)
    cleanup()
    cleanup = undefined
  })
})
