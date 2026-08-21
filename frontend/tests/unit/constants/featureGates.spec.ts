import { describe, it, expect } from 'vitest'
import {
  GATE_ROUTE_MAP,
  GATE_FALLBACK_PATH,
  matchGateKey,
  prefixCovers,
  buildGateRouteRules,
  decideGate,
} from '~/constants/featureGates'

/**
 * Gate 基盤工事② route 束縛表の純関数ユニットテスト（試練・red 先行）。
 *
 * AC-5  プレフィクス境界（前方一致の巻き込み禁止）
 * AC-8  SSR 時の挙動の固定（未公開コンテンツを SSR 出力しない）
 */
describe('featureGates 定数と純関数', () => {
  it('GATE_ROUTE_MAP のキーは SCREAMING_SNAKE で重複が無く、値は絶対パスのプレフィクス', () => {
    const keys = Object.keys(GATE_ROUTE_MAP)
    expect(keys.length).toBeGreaterThan(0)
    expect(new Set(keys).size).toBe(keys.length)
    for (const key of keys) {
      expect(key).toMatch(/^FEATURE_[A-Z0-9_]+_ENABLED$/)
      expect(GATE_ROUTE_MAP[key]!.length).toBeGreaterThan(0)
      for (const prefix of GATE_ROUTE_MAP[key]!) {
        // 静的セグメントは kebab、動的セグメントは `*`（1セグメントちょうど）。
        expect(prefix).toMatch(/^\/([a-z0-9-]+|\*)(\/([a-z0-9-]+|\*))*$/)
      }
    }
  })

  it('同一プレフィクスが複数の gate_key に重複登録されていない', () => {
    const all = Object.values(GATE_ROUTE_MAP).flat()
    expect(new Set(all).size).toBe(all.length)
  })

  it('(AC-5) 完全一致とサブパスは一致する', () => {
    expect(matchGateKey('/shift')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/shift/')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/shift/123')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/shift/123/edit')).toBe('FEATURE_SHIFT_ENABLED')
  })

  it('(AC-5) 隣接名を巻き込まない（/todo が /todo-memo・/todos を巻き込まない相当）', () => {
    // 実登録されている /market を使った境界検証
    expect(matchGateKey('/market')).toBe('FEATURE_MARKET_ENABLED')
    expect(matchGateKey('/marketing')).toBeNull()
    expect(matchGateKey('/markets')).toBeNull()
    expect(matchGateKey('/market-research')).toBeNull()
    // 未登録の兄弟パスは常に null
    expect(matchGateKey('/shifted')).toBeNull()
    expect(matchGateKey('/shifts')).toBeNull()
  })

  it('ガード対象外のパスは null を返す', () => {
    expect(matchGateKey('/')).toBeNull()
    expect(matchGateKey('/dashboard')).toBeNull()
    expect(matchGateKey('/teams/foo/settings')).toBeNull()
    expect(matchGateKey('/login')).toBeNull()
  })

  it('GATE_FALLBACK_PATH はガード対象外である（拒否時の無限リダイレクト防止）', () => {
    expect(matchGateKey(GATE_FALLBACK_PATH)).toBeNull()
  })

  it('(AC-8) 静的プレフィクスには ssr:false の routeRule が生成される', () => {
    const rules = buildGateRouteRules()
    for (const prefixes of Object.values(GATE_ROUTE_MAP)) {
      for (const prefix of prefixes.filter((p) => !p.includes('*'))) {
        expect(rules[prefix]).toEqual({ ssr: false })
        expect(rules[`${prefix}/**`]).toEqual({ ssr: false })
      }
    }
  })

  it('動的セグメントを含むプレフィクスは routeRules に出さない（slug-redirect の SSR 301 を壊さない）', () => {
    const rules = buildGateRouteRules()
    for (const key of Object.keys(rules)) {
      expect(key).not.toContain('*/')
    }
    // /teams/** を丸ごと ssr:false にするとチーム配下の SSR 301 が壊れるため、出さない。
    expect(rules['/teams/**']).toBeUndefined()
    expect(rules['/organizations/**']).toBeUndefined()
  })

  /**
   * SSR 抑止の守備範囲が「全域」ではないことを、件数比ごと固定する。
   *
   * routeRules に出せるのは静的プレフィクスのみで、動的セグメントを含む経路は
   * **SSR 抑止も middleware 判定も掛からず**、クライアント側判定だけに依存する
   * （middleware は SSR では ssr-defer で一切判定しない）。
   * この非対称性は doc に明記してあるが、内訳が変わったのに doc が古いまま残ることを
   * 防ぐため、テストでも固定しておく。
   * 束縛を増減させた場合はこの期待値と doc の両方を必ず更新すること。
   */
  it('SSR 抑止が掛かるのは静的プレフィクスのみで、動的セグメント経路は対象外である', () => {
    const all = Object.values(GATE_ROUTE_MAP).flat()
    const dynamic = all.filter((p) => p.includes('*'))
    const staticOnly = all.filter((p) => !p.includes('*'))

    // 実測の内訳（doc・PR 本文・Issue と数値を揃えてある）。
    expect(all).toHaveLength(91)
    expect(staticOnly).toHaveLength(47)
    expect(dynamic).toHaveLength(44)

    const rules = buildGateRouteRules()
    // 静的プレフィクスは 1 件につき `/x` と `/x/**` の 2 エントリを生む。
    expect(Object.keys(rules)).toHaveLength(staticOnly.length * 2)

    // 動的プレフィクスは 1 件も routeRules に現れない = SSR 抑止の対象外。
    for (const prefix of dynamic) {
      expect(rules[prefix]).toBeUndefined()
      expect(rules[`${prefix}/**`]).toBeUndefined()
    }

    // 「約半分が抑止対象外」という認識が崩れていないことの歯止め。
    expect(dynamic.length).toBeGreaterThan(all.length * 0.4)
  })

  it('動的セグメントは1セグメントちょうどに一致する', () => {
    expect(prefixCovers('/teams/*/shifts', '/teams/my-team/shifts')).toBe(true)
    expect(prefixCovers('/teams/*/shifts', '/teams/my-team/shifts/1/board')).toBe(true)
    expect(prefixCovers('/teams/*/shifts', '/teams/my-team/settings')).toBe(false)
    expect(prefixCovers('/teams/*/shifts', '/teams/shifts')).toBe(false)
    expect(matchGateKey('/teams/my-team/shifts')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/teams/my-team/settings/shift')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/teams/my-team/members')).toBeNull()
  })

  it('御裁可1: 入口以外の素通りページも覆われている', () => {
    expect(matchGateKey('/my/shift')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/my/shifts')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/my/shift-availability')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/admin/shift-budget/alerts')).toBe('FEATURE_SHIFT_ENABLED')
    expect(matchGateKey('/settings/billing')).toBe('FEATURE_BILLING_PAYMENT_ENABLED')
    expect(matchGateKey('/wallet/cards/new')).toBe('FEATURE_BILLING_PAYMENT_ENABLED')
    expect(matchGateKey('/me/jobs')).toBe('FEATURE_MATCHING_ENABLED')
    expect(matchGateKey('/me/recruitment-feed')).toBe('FEATURE_RECRUITMENT_ENABLED')
    expect(matchGateKey('/me/care-links/invite-watcher')).toBe('FEATURE_FAMILY_CARE_ENABLED')
    expect(matchGateKey('/me/ad-deliveries')).toBe('FEATURE_PROMOTION_ENABLED')
    expect(matchGateKey('/my/resume/1/preview')).toBe('FEATURE_SKILL_RESUME_ENABLED')
    expect(matchGateKey('/settings/calendar-sync')).toBe('FEATURE_WEBHOOK_SYNC_ENABLED')
  })

  it('未ログイン導線と中核機能は束縛しない', () => {
    // 配信停止・招待受諾は auth:false の公開導線（台帳 route_coverage_exclusions で宣言済み）。
    expect(matchGateKey('/ads/unsubscribe')).toBeNull()
    expect(matchGateKey('/care-links/invitations/abc123')).toBeNull()
    // 探索・検索は中核機能なので隔離しない。
    expect(matchGateKey('/search')).toBeNull()
    expect(matchGateKey('/teams/search')).toBeNull()
    expect(matchGateKey('/organizations/search')).toBeNull()
    expect(matchGateKey('/dashboard')).toBeNull()
    expect(matchGateKey('/teams/my-team')).toBeNull()
  })

  it('(AC-8) SSR ではフラグを評価できないため ssr-defer を返す（素通り=pass にしない）', () => {
    const decision = decideGate({
      path: '/shift/1',
      isServer: true,
      isAuthenticated: true,
      publicLoaded: false,
      enabled: () => {
        throw new Error('SSR ではフラグを評価してはならない')
      },
    })
    expect(decision).toEqual({ action: 'ssr-defer' })
  })

  it('ガード対象外パスは SSR/CSR とも pass', () => {
    expect(decideGate({ path: '/dashboard', isServer: true, isAuthenticated: true, publicLoaded: false, enabled: () => false }))
      .toEqual({ action: 'pass' })
    expect(decideGate({ path: '/dashboard', isServer: false, isAuthenticated: true, publicLoaded: false, enabled: () => false }))
      .toEqual({ action: 'pass' })
  })

  it('未認証のガード対象パスは pass（後段の auth に委ねる。503 に化けさせない）', () => {
    expect(decideGate({
      path: '/contracts/123',
      isServer: false,
      isAuthenticated: false,
      publicLoaded: false,
      enabled: () => {
        throw new Error('未認証ではフラグを評価してはならない')
      },
    })).toEqual({ action: 'pass' })
  })

  it('未取得のガード対象パスは ensure（fail-open にしない）', () => {
    expect(decideGate({ path: '/market', isServer: false, isAuthenticated: true, publicLoaded: false, enabled: () => true }))
      .toEqual({ action: 'ensure' })
  })

  it('取得済みなら enabled の真偽で pass / deny に分かれる', () => {
    expect(decideGate({ path: '/market', isServer: false, isAuthenticated: true, publicLoaded: true, enabled: () => true }))
      .toEqual({ action: 'pass' })
    expect(decideGate({ path: '/market', isServer: false, isAuthenticated: true, publicLoaded: true, enabled: () => false }))
      .toEqual({ action: 'deny', gateKey: 'FEATURE_MARKET_ENABLED' })
  })
})
