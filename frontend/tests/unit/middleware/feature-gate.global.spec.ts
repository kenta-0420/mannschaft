import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import featureGateMiddleware from '~/middleware/feature-gate.global'
import { GATE_FALLBACK_PATH } from '~/constants/featureGates'

/**
 * Gate 基盤工事② route ガード middleware のユニットテスト（試練・red 先行）。
 *
 * AC-1 default export が defineNuxtRouteMiddleware の戻り値であること
 * AC-2 enabled:true + 取得済み → undefined（素通り）
 * AC-3 enabled:false + 取得済み → navigateTo + $toast.add(severity:'error') 1回・文言は t() 経由
 * AC-4 対象外パスではフラグ取得関数が一度も呼ばれない
 * AC-6 未取得のガード対象パスでは loadPublicFlags を少なくとも1回試行する
 * AC-7 loadPublicFlags reject → navigateTo せず 503 fatal を throw
 *
 * ## useNuxtApp を mockNuxtImport で差し替えていない理由
 * `useNuxtApp` を差し替えると @nuxt/test-utils の setupNuxt 自身が壊れ
 * （"Cannot read properties of undefined (reading 'afterEach')"）、ファイルごと
 * skip されて**テストが一件も走らないまま緑に見える**偽陰性になる。
 * よって nuxtApp は書き換えず、$i18n は本物を使い、$toast だけを用意する（installToast 参照）。
 */

const mockNavigateTo = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigateTo(...args))


interface StoreStub {
  publicLoaded: boolean
  isEnabled: (key: string) => boolean
  loadPublicFlags: () => Promise<void>
}

let storeStub: StoreStub
mockNuxtImport('useFeatureFlagStore', () => () => storeStub)

let authStub: { isAuthenticated: boolean }
mockNuxtImport('useAuthStore', () => () => authStub)

function route(path: string): RouteLocationNormalized {
  return { path, fullPath: path } as unknown as RouteLocationNormalized
}

const from = route('/dashboard')

function makeStore(overrides: Partial<StoreStub> = {}): StoreStub {
  return {
    publicLoaded: true,
    isEnabled: vi.fn(() => true),
    loadPublicFlags: vi.fn(async () => {}),
    ...overrides,
  }
}

/**
 * 本物の nuxtApp に $toast を用意する。
 *
 * ## nuxtApp を書き換えない方針にした理由（実測で分かった罠）
 * - `mockNuxtImport('useNuxtApp', ...)` は @nuxt/test-utils の setupNuxt 自身を壊す
 *   （"Cannot read properties of undefined (reading 'afterEach')"）。ファイルごと skip され
 *   **テストが一件も走らないまま緑に見える**偽陰性になる。
 * - `app.$i18n = ...` は getter 専用で代入できず、`Object.defineProperty` も
 *   non-configurable のため "Cannot redefine property" になる。
 * - `nuxtApp.provide()` で注入した `$toast` は **non-configurable なので delete できない**
 *   （"Cannot delete property '$toast'"）。後始末で delete すると例外になり、
 *   **spy がリセットされないまま次のテストへ漏れる**（前のテストのトースト呼び出しが
 *   次のテストの「呼ばれていないこと」の検証を偽陽性で落とす）。
 *
 * よって注入は**ファイル内で一度だけ**行い、テスト間はスパイの mockReset で切り分ける。
 */
let sharedToastAdd: ReturnType<typeof vi.fn> | undefined

function installToast(): { add: ReturnType<typeof vi.fn> } {
  const nuxtApp = useNuxtApp()
  const holder = nuxtApp as unknown as Record<string, unknown>

  if (!sharedToastAdd) {
    sharedToastAdd = vi.fn()
    const existing = holder.$toast as { add?: unknown } | undefined
    if (existing && typeof existing.add === 'function') {
      // 既に $toast があるなら add だけ差し替える（provide は二重注入で例外になる）。
      ;(existing as { add: unknown }).add = sharedToastAdd
    } else {
      nuxtApp.provide('toast', { add: sharedToastAdd })
    }
  }

  sharedToastAdd.mockReset()
  return { add: sharedToastAdd }
}

/** i18n の実解決値。文言が i18n キー経由であることの裏取りに使う。 */
function translated(key: string): string {
  const nuxtApp = useNuxtApp() as unknown as { $i18n: { t: (k: string) => string } }
  return nuxtApp.$i18n.t(key)
}

let toastHandle: { add: ReturnType<typeof vi.fn> } | undefined

describe('feature-gate.global ミドルウェア', () => {
  beforeEach(() => {
    mockNavigateTo.mockReset()
    storeStub = makeStore()
    authStub = { isAuthenticated: true }
  })

  afterEach(() => {
    // provide した $toast は delete できないため、スパイのリセットで切り分ける。
    sharedToastAdd?.mockReset()
    toastHandle = undefined
  })

  it('(AC-1) defineNuxtRouteMiddleware の戻り値を default export する', () => {
    expect(typeof featureGateMiddleware).toBe('function')
  })

  it('(AC-2) 取得済みかつ enabled:true なら undefined を返して素通りする', async () => {
    toastHandle = installToast()
    storeStub = makeStore({ publicLoaded: true, isEnabled: vi.fn(() => true) })

    const result = await featureGateMiddleware(route('/shift/12'), from)

    expect(result).toBeUndefined()
    expect(mockNavigateTo).not.toHaveBeenCalled()
    expect(toastHandle!.add).not.toHaveBeenCalled()
  })

  it('(AC-3) 取得済みかつ enabled:false なら既定の戻り先へ navigateTo し、error トーストを1回出す', async () => {
    toastHandle = installToast()
    storeStub = makeStore({ publicLoaded: true, isEnabled: vi.fn(() => false) })

    await featureGateMiddleware(route('/shift/12'), from)

    expect(mockNavigateTo).toHaveBeenCalledTimes(1)
    expect(mockNavigateTo).toHaveBeenCalledWith(GATE_FALLBACK_PATH, { replace: true })

    expect(toastHandle!.add).toHaveBeenCalledTimes(1)
    const opts = toastHandle!.add.mock.calls[0]![0] as Record<string, unknown>
    expect(opts.severity).toBe('error')

    // 文言が i18n キー経由であることの裏取り（ソースにリテラル日本語が無いことの証跡）。
    // i18n が実際に解決した値と一致し、かつ「キーがそのまま出ている」状態でもないこと。
    expect(opts.summary).toBe(translated('featureGate.blocked.title'))
    expect(opts.detail).toBe(translated('featureGate.blocked.body'))
    expect(opts.summary).not.toBe('featureGate.blocked.title')
    expect(opts.detail).not.toBe('featureGate.blocked.body')
  })

  /**
   * `$toast` が undefined のケース（`admin-console.ts:51-54` と同じ防御）は、
   * Nuxt テスト環境では nuxtApp に一度注入した `$toast` を削除できない
   * （provide したプロパティは non-configurable）ため、直接は再現できない。
   * ここでは「トーストの有無に関わらず戻し処理は必ず行われる」という
   * 観測可能な契約だけを固定する。undefined 分岐そのものは実装側の optional 参照で担保する。
   */
  it('(AC-3 補) 未公開なら戻し処理は必ず行われる（トースト表示は副次的）', async () => {
    toastHandle = installToast()
    storeStub = makeStore({ publicLoaded: true, isEnabled: vi.fn(() => false) })

    await expect(featureGateMiddleware(route('/market'), from)).resolves.not.toThrow()
    expect(mockNavigateTo).toHaveBeenCalledTimes(1)
    expect(mockNavigateTo).toHaveBeenCalledWith(GATE_FALLBACK_PATH, { replace: true })
  })

  it('(重大5) 未認証でガード対象パスを直打ちしても 503 にせず素通りする（auth に委ねる）', async () => {
    toastHandle = installToast()
    authStub = { isAuthenticated: false }
    const loadPublicFlags = vi.fn(async () => {
      throw new Error('401 Unauthorized')
    })
    const isEnabled = vi.fn(() => false)
    storeStub = makeStore({ publicLoaded: false, loadPublicFlags, isEnabled })

    // メール内リンクをログアウト状態で開くシナリオ。
    const result = await featureGateMiddleware(route('/contracts/123'), from)

    expect(result).toBeUndefined()
    // 認証必須の公開フラグ API を叩かない（叩くと 401 → 503 フルページになる）。
    expect(loadPublicFlags).not.toHaveBeenCalled()
    expect(isEnabled).not.toHaveBeenCalled()
    // 戻しもトーストもせず、後段の auth ミドルウェアに /login 誘導を任せる。
    expect(mockNavigateTo).not.toHaveBeenCalled()
    expect(toastHandle!.add).not.toHaveBeenCalled()
  })

  it('(AC-4) ガード対象外パスではフラグ取得関数が一度も呼ばれない', async () => {
    toastHandle = installToast()
    const loadPublicFlags = vi.fn(async () => {})
    const isEnabled = vi.fn(() => true)
    storeStub = makeStore({ publicLoaded: false, loadPublicFlags, isEnabled })

    const result = await featureGateMiddleware(route('/dashboard'), from)

    expect(result).toBeUndefined()
    expect(loadPublicFlags).not.toHaveBeenCalled()
    expect(isEnabled).not.toHaveBeenCalled()
    expect(mockNavigateTo).not.toHaveBeenCalled()
  })

  it('(AC-6) 未取得のガード対象パスでは loadPublicFlags を少なくとも1回試行する', async () => {
    toastHandle = installToast()
    const loadPublicFlags = vi.fn(async () => {
      storeStub.publicLoaded = true
    })
    storeStub = makeStore({ publicLoaded: false, loadPublicFlags, isEnabled: vi.fn(() => true) })

    const result = await featureGateMiddleware(route('/market'), from)

    expect(loadPublicFlags).toHaveBeenCalledTimes(1)
    expect(result).toBeUndefined()
    expect(mockNavigateTo).not.toHaveBeenCalled()
  })

  it('(AC-6 補) 取得後もフラグが確定しなければ fail-closed で戻す（素通りさせない）', async () => {
    toastHandle = installToast()
    const loadPublicFlags = vi.fn(async () => {
      // publicLoaded を立てないまま解決した異常系
    })
    storeStub = makeStore({ publicLoaded: false, loadPublicFlags, isEnabled: vi.fn(() => true) })

    await featureGateMiddleware(route('/market'), from)

    expect(mockNavigateTo).toHaveBeenCalledTimes(1)
  })

  it('(AC-7) loadPublicFlags が reject したら navigateTo せず 503 fatal を throw する', async () => {
    toastHandle = installToast()
    const loadPublicFlags = vi.fn(async () => {
      throw new Error('network error')
    })
    storeStub = makeStore({ publicLoaded: false, loadPublicFlags })

    let thrown: unknown
    try {
      await featureGateMiddleware(route('/market'), from)
    } catch (error) {
      thrown = error
    }

    const err = thrown as { statusCode?: number, fatal?: boolean } | undefined
    expect(err).toBeDefined()
    expect(err?.statusCode).toBe(503)
    expect(err?.fatal).toBe(true)
    expect(mockNavigateTo).not.toHaveBeenCalled()
    expect(toastHandle!.add).not.toHaveBeenCalled()
  })
})
