import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'

/**
 * F08.9 P5 加入ページ pages/payments/subscribe/[itemId].vue のフロー UT。
 *
 * 検証観点:
 *   SUB-001: 受益者=自分（既定）で次へ → createSetupIntent が呼ばれ card ステップへ
 *   SUB-002: カード success → confirmPaymentMethod → subscribe の順で呼ばれ完了ステップへ
 *   SUB-003: subscribe を呼ぶ body は beneficiaryUserId=自分・idempotencyKey 付き
 *   SUB-004: 後見下の子を選んで加入 → beneficiaryUserId=子 ID で subscribe
 *   SUB-005: 409 ALREADY_EXISTS → errorAlreadyExists 文言を表示し card ステップへ戻る
 *   SUB-006: 409 NOT_RECURRING → errorNotRecurring 文言にマップ
 *   SUB-007: 3DS 復帰（setup_intent_client_secret クエリ・succeeded）→ confirm→subscribe 続行
 *   SUB-008: 3DS 復帰で router.replace によりクエリが除去される（secret を残さない）
 *   SUB-010: router.replace が reject しても加入が完了し console.warn が呼ばれる
 *   SUB-011: 3DS 復帰クエリの beneficiaryUserId が switchableChildren に無い → SELF フォールバック＋console.warn
 */

// ── Stripe / API / guardianship / auth / notification のモック ──
const mockCreateSetupIntent = vi.fn()
const mockConfirmPaymentMethod = vi.fn()
const mockSubscribe = vi.fn()
vi.mock('~/composables/useMembershipSubscriptionApi', () => ({
  useMembershipSubscriptionApi: () => ({
    createSetupIntent: mockCreateSetupIntent,
    confirmPaymentMethod: mockConfirmPaymentMethod,
    subscribe: mockSubscribe,
    listMySubscriptions: vi.fn(),
    cancelSubscription: vi.fn(),
    skipSubscription: vi.fn(),
    resumeSubscription: vi.fn(),
  }),
}))

const mockListSwitchableChildren = vi.fn()
vi.mock('~/composables/useGuardianshipApi', () => ({
  useGuardianshipApi: () => ({
    listSwitchableChildren: mockListSwitchableChildren,
  }),
}))

const mockRetrieveSetupIntent = vi.fn()
vi.mock('~/composables/useStripeSetup', () => ({
  useStripeSetup: () => ({
    retrieveSetupIntent: mockRetrieveSetupIntent,
    getStripe: vi.fn(),
    mountPaymentElement: vi.fn(),
    confirmSetup: vi.fn(),
  }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    user: { id: 42, fullName: 'テスト 太郎' },
    loadFromStorage: vi.fn(),
  }),
}))

// useRoute を制御可能にする（3DS 復帰クエリの注入のため）。
// mountSuspended の route オプションは useRoute().query へ確実に反映されないため明示モックする。
// useRouter は Nuxt 内部プラグインが afterEach/beforeResolve 等を使うため、
// これらすべてのメソッドを持つ完全なモックオブジェクトを返す必要がある。
const routeState = {
  path: '/payments/subscribe/7',
  params: { itemId: '7' } as Record<string, string>,
  query: {} as Record<string, string>,
}
mockNuxtImport('useRoute', () => () => routeState)

// router.replace を制御可能にするための vi.fn()。
// SUB-010 で reject させて console.warn を検証する。
const mockRouterReplace = vi.fn().mockResolvedValue(undefined)

// useRouter は Nuxt 内部プラグイン（chunk-reload, navigation-repaint 等）が
// afterEach/beforeResolve 等を使うため、これらすべてを vi.fn() として含むモックを返す。
mockNuxtImport('useRouter', () => () => ({
  replace: (...args: unknown[]) => mockRouterReplace(...args),
  push: vi.fn().mockResolvedValue(undefined),
  go: vi.fn(),
  back: vi.fn(),
  forward: vi.fn(),
  beforeEach: vi.fn().mockReturnValue(vi.fn()),
  afterEach: vi.fn().mockReturnValue(vi.fn()),
  beforeResolve: vi.fn().mockReturnValue(vi.fn()),
  onError: vi.fn().mockReturnValue(vi.fn()),
  addRoute: vi.fn(),
  removeRoute: vi.fn(),
  hasRoute: vi.fn().mockReturnValue(false),
  getRoutes: vi.fn().mockReturnValue([]),
  resolve: vi.fn().mockReturnValue({ href: '/', fullPath: '/', matched: [], params: {}, query: {}, hash: '', meta: {}, name: undefined, redirectedFrom: undefined }),
  currentRoute: { value: routeState },
  isReady: vi.fn().mockResolvedValue(undefined),
}))

// StripePaymentForm は Stripe.js に依存するため、success/error を手動 emit できる stub に置換する。
const StripePaymentFormStub = defineComponent({
  name: 'StripePaymentForm',
  props: { clientSecret: { type: String, required: true }, returnUrl: { type: String, required: true } },
  emits: ['success', 'error'],
  template: '<div data-testid="stripe-form-stub" />',
})

const SubscribePage = (await import('~/pages/payments/subscribe/[itemId].vue')).default

/** mountSuspended の共通オプション（StripePaymentForm を stub に差し替え）。 */
function mountWith(routeQuery: Record<string, string> = {}) {
  routeState.path = '/payments/subscribe/7'
  routeState.params = { itemId: '7' }
  routeState.query = { ...routeQuery }
  return mountSuspended(SubscribePage, {
    global: { stubs: { StripePaymentForm: StripePaymentFormStub } },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockCreateSetupIntent.mockReset()
  mockConfirmPaymentMethod.mockReset()
  mockSubscribe.mockReset()
  mockListSwitchableChildren.mockReset()
  mockRetrieveSetupIntent.mockReset()
  mockRouterReplace.mockReset().mockResolvedValue(undefined)

  mockListSwitchableChildren.mockResolvedValue({ data: { children: [], blockedChildren: [] } })
  mockCreateSetupIntent.mockResolvedValue({ data: { clientSecret: 'seti_secret_1', setupIntentId: 'seti_1', status: 'requires_payment_method' } })
  mockConfirmPaymentMethod.mockResolvedValue({ data: { saved: true, defaultPaymentMethod: 'pm_1' } })
  mockSubscribe.mockResolvedValue({ data: { id: 'sub_1' } })
})

/** subscribe 呼び出しの body（型安全に取り出すためのヘルパー）。 */
function subscribeBody(callIndex = 0): { beneficiaryUserId: number; idempotencyKey?: string } {
  return mockSubscribe.mock.calls[callIndex]![1] as {
    beneficiaryUserId: number
    idempotencyKey?: string
  }
}

// テスト環境では i18n の locale messages を読み込まないため $t はキーをそのまま返す。
// 文言は data-testid とエラー i18n キー（rendered = key）で検証する。
type Wrapper = Awaited<ReturnType<typeof mountWith>>

async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) await nextTick()
}

async function clickNext(wrapper: Wrapper): Promise<void> {
  const next = wrapper.find('[data-testid="subscribe-next"]')
  expect(next.exists()).toBe(true)
  await next.trigger('click')
  await flush()
}

function emitCardSuccess(wrapper: Wrapper, pm: string): void {
  wrapper.findComponent(StripePaymentFormStub).vm.$emit('success', pm)
}

function errorText(wrapper: Wrapper): string {
  return wrapper.find('[data-testid="subscribe-error"]').text()
}

describe('pages/payments/subscribe/[itemId].vue', () => {
  it('SUB-001: 受益者=自分（既定）で次へ → createSetupIntent → card ステップ', async () => {
    const wrapper = await mountWith()
    await flush()

    await clickNext(wrapper)

    expect(mockCreateSetupIntent).toHaveBeenCalledTimes(1)
    expect(wrapper.findComponent(StripePaymentFormStub).exists()).toBe(true)
  })

  it('SUB-002/003: card success → confirm → subscribe の順・body は自分 ID＋idempotencyKey', async () => {
    const wrapper = await mountWith()
    await flush()
    await clickNext(wrapper)

    // StripePaymentForm が success を emit（3DS 不要カードの非リダイレクト成功）。
    emitCardSuccess(wrapper, 'pm_card_1')
    await flush()

    expect(mockConfirmPaymentMethod).toHaveBeenCalledWith({ paymentMethodId: 'pm_card_1' })
    expect(mockSubscribe).toHaveBeenCalledTimes(1)
    // 呼び出し順: confirm が subscribe より前。
    expect(mockConfirmPaymentMethod.mock.invocationCallOrder[0]!)
      .toBeLessThan(mockSubscribe.mock.invocationCallOrder[0]!)

    expect(mockSubscribe.mock.calls[0]![0]).toBe(7)
    const body = subscribeBody()
    expect(body.beneficiaryUserId).toBe(42)
    expect(typeof body.idempotencyKey).toBe('string')
    expect((body.idempotencyKey ?? '').length).toBeGreaterThan(0)

    // 完了ステップが表示される。
    expect(wrapper.find('[data-testid="subscribe-done"]').exists()).toBe(true)
  })

  it('SUB-004: 後見下の子を選んで加入 → beneficiaryUserId=子 ID で subscribe', async () => {
    mockListSwitchableChildren.mockResolvedValue({
      data: {
        children: [{ childUserId: 99, displayName: '子 花子', stageKey: 'elementary', switchAllowed: true }],
        blockedChildren: [],
      },
    })
    const wrapper = await mountWith()
    await flush()

    // 子のラジオ（2 番目）を選択する。
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios.length).toBe(2)
    await radios[1]!.setValue()
    await flush()

    await clickNext(wrapper)
    emitCardSuccess(wrapper, 'pm_card_2')
    await flush()

    expect(subscribeBody().beneficiaryUserId).toBe(99)
  })

  it('SUB-005: 409 ALREADY_EXISTS → errorAlreadyExists 文言を表示し card へ戻る', async () => {
    mockSubscribe.mockRejectedValue({ data: { error: { code: 'MEMBERSHIP_BILLING_021' } } })
    const wrapper = await mountWith()
    await flush()
    await clickNext(wrapper)
    emitCardSuccess(wrapper, 'pm_card_3')
    await flush()

    expect(errorText(wrapper)).toContain('An active recurring payment already exists')
    // card ステップに戻り再試行できる（SetupIntent 再作成）。
    expect(wrapper.findComponent(StripePaymentFormStub).exists()).toBe(true)
  })

  it('SUB-006: 409 NOT_RECURRING → errorNotRecurring 文言にマップ', async () => {
    mockSubscribe.mockRejectedValue({ data: { error: { code: 'MEMBERSHIP_BILLING_019' } } })
    const wrapper = await mountWith()
    await flush()
    await clickNext(wrapper)
    emitCardSuccess(wrapper, 'pm_card_4')
    await flush()

    expect(errorText(wrapper)).toContain('This fee does not support recurring payments')
  })

  it('SUB-007/008: 3DS 復帰（succeeded）→ confirm→subscribe 続行・既存 SetupIntent を retrieve', async () => {
    mockRetrieveSetupIntent.mockResolvedValue({
      status: 'ok',
      setupIntent: { id: 'seti_1', status: 'succeeded', payment_method: 'pm_3ds' },
    })
    const wrapper = await mountWith({
      setup_intent_client_secret: 'seti_secret_3ds',
      beneficiaryUserId: '42',
    })
    // onMounted の復帰処理を待つ。
    await flush()

    expect(mockRetrieveSetupIntent).toHaveBeenCalledWith('seti_secret_3ds')
    expect(mockConfirmPaymentMethod).toHaveBeenCalledWith({ paymentMethodId: 'pm_3ds' })
    expect(mockSubscribe).toHaveBeenCalledTimes(1)
    expect(subscribeBody().beneficiaryUserId).toBe(42)

    // 復帰時は createSetupIntent を呼ばない（既存 SetupIntent を retrieve するため）。
    expect(mockCreateSetupIntent).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="subscribe-done"]').exists()).toBe(true)
  })

  it('SUB-009: 3DS 復帰で認証失敗（requires_payment_method）→ authFailed 表示・再試行可', async () => {
    mockRetrieveSetupIntent.mockResolvedValue({
      status: 'ok',
      setupIntent: { id: 'seti_1', status: 'requires_payment_method', payment_method: null },
    })
    const wrapper = await mountWith({
      setup_intent_client_secret: 'seti_secret_fail',
      beneficiaryUserId: '42',
    })
    await flush()

    expect(mockSubscribe).not.toHaveBeenCalled()
    expect(errorText(wrapper)).toContain('Card authentication was not completed')
  })

  it('SUB-010: router.replace が reject しても加入が完了し console.warn が呼ばれる', async () => {
    // 前テスト（SUB-009）の非同期残存処理が確実に終わるよう複数 tick 待機する。
    // SUB-009 の handleRedirectReturn は loadSwitchableChildren / router.replace /
    // retrieveSetupIntent / finalizeSubscription と多段 await を持つため、
    // 残存処理が SUB-010 の mock 設定に干渉しないようにする。
    await flush(10)

    // replace を reject させる（重複ナビゲーション等のシミュレーション）。
    // @nuxt/test-utils の mountSuspended 内部でも router.replace が呼ばれるため、
    // 最初の呼び出し（Nuxt 内部）は成功させ、以降（コンポーネントの handleRedirectReturn）は reject させる。
    let replaceCallCount = 0
    mockRouterReplace.mockImplementation(() => {
      replaceCallCount++
      if (replaceCallCount === 1) return Promise.resolve(undefined)
      return Promise.reject(new Error('NavigationDuplicated: Avoided redundant navigation'))
    })
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    mockRetrieveSetupIntent.mockResolvedValue({
      status: 'ok',
      setupIntent: { id: 'seti_1', status: 'succeeded', payment_method: 'pm_warn_test' },
    })
    const wrapper = await mountWith({
      setup_intent_client_secret: 'seti_secret_warn',
      beneficiaryUserId: '42',
    })
    await flush(10)

    // replace 失敗でも加入フローが継続し完了ステップが表示される。
    expect(wrapper.find('[data-testid="subscribe-done"]').exists()).toBe(true)
    expect(mockSubscribe).toHaveBeenCalledTimes(1)

    // console.warn が呼ばれ、path は含まれるが secret 値は含まれないこと。
    expect(warnSpy).toHaveBeenCalled()
    const warnArgs = warnSpy.mock.calls.find((args) =>
      String(args[0]).includes('secret クエリの URL 除去に失敗した'),
    )
    expect(warnArgs).toBeDefined()
    // ログに client_secret の値（'seti_secret_warn'）が含まれていないこと。
    expect(JSON.stringify(warnArgs)).not.toContain('seti_secret_warn')

    warnSpy.mockRestore()
  })

  it('SUB-011: 3DS 復帰クエリの beneficiaryUserId が switchableChildren に無い → SELF フォールバック＋console.warn', async () => {
    // switchableChildren は userId=42（自分）とも子 ID とも一致しない ID=999 を返すクエリ。
    mockListSwitchableChildren.mockResolvedValue({
      data: {
        children: [{ childUserId: 100, displayName: '子 花子', stageKey: 'elementary', switchAllowed: true }],
        blockedChildren: [],
      },
    })
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    mockRetrieveSetupIntent.mockResolvedValue({
      status: 'ok',
      setupIntent: { id: 'seti_1', status: 'succeeded', payment_method: 'pm_selffall' },
    })
    // beneficiaryUserId=999 → 自分(42)でも子(100)でもない → SELF フォールバック。
    await mountWith({
      setup_intent_client_secret: 'seti_secret_unknown',
      beneficiaryUserId: '999',
    })
    await flush()

    // SELF にフォールバックしているため subscribeBody は自分の ID(42) になる。
    expect(mockSubscribe).toHaveBeenCalledTimes(1)
    expect(subscribeBody().beneficiaryUserId).toBe(42)

    // console.warn が呼ばれ、receivedBeneficiaryUserId が含まれること。
    expect(warnSpy).toHaveBeenCalled()
    const warnArgs = warnSpy.mock.calls.find((args) =>
      String(args[0]).includes('ホワイトリスト外のため SELF にフォールバック'),
    )
    expect(warnArgs).toBeDefined()
    // ログにパスが含まれること。
    expect(JSON.stringify(warnArgs)).toContain(routeState.path)

    warnSpy.mockRestore()
  })
})
