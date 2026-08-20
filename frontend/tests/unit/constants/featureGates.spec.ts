import { describe, it, expect } from 'vitest'
import {
  GATE_ROUTE_MAP,
  GATE_FALLBACK_PATH,
  matchGateKey,
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
      expect(key).toMatch(/^[A-Z][A-Z0-9_]*$/)
      expect(GATE_ROUTE_MAP[key]!.length).toBeGreaterThan(0)
      for (const prefix of GATE_ROUTE_MAP[key]!) {
        expect(prefix).toMatch(/^\/[a-z0-9-]+(\/[a-z0-9-]+)*$/)
      }
    }
  })

  it('同一プレフィクスが複数の gate_key に重複登録されていない', () => {
    const all = Object.values(GATE_ROUTE_MAP).flat()
    expect(new Set(all).size).toBe(all.length)
  })

  it('(AC-5) 完全一致とサブパスは一致する', () => {
    expect(matchGateKey('/shift')).toBe('SHIFT')
    expect(matchGateKey('/shift/')).toBe('SHIFT')
    expect(matchGateKey('/shift/123')).toBe('SHIFT')
    expect(matchGateKey('/shift/123/edit')).toBe('SHIFT')
  })

  it('(AC-5) 隣接名を巻き込まない（/todo が /todo-memo・/todos を巻き込まない相当）', () => {
    // 実登録されている /market を使った境界検証
    expect(matchGateKey('/market')).toBe('MARKET')
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

  it('(AC-8) 全ガード対象プレフィクスに ssr:false の routeRule が生成される', () => {
    const rules = buildGateRouteRules()
    for (const prefixes of Object.values(GATE_ROUTE_MAP)) {
      for (const prefix of prefixes) {
        expect(rules[prefix]).toEqual({ ssr: false })
        expect(rules[`${prefix}/**`]).toEqual({ ssr: false })
      }
    }
  })

  it('(AC-8) SSR ではフラグを評価できないため ssr-defer を返す（素通り=pass にしない）', () => {
    const decision = decideGate({
      path: '/shift/1',
      isServer: true,
      publicLoaded: false,
      enabled: () => {
        throw new Error('SSR ではフラグを評価してはならない')
      },
    })
    expect(decision).toEqual({ action: 'ssr-defer', gateKey: 'SHIFT' })
  })

  it('ガード対象外パスは SSR/CSR とも pass', () => {
    expect(decideGate({ path: '/dashboard', isServer: true, publicLoaded: false, enabled: () => false }))
      .toEqual({ action: 'pass' })
    expect(decideGate({ path: '/dashboard', isServer: false, publicLoaded: false, enabled: () => false }))
      .toEqual({ action: 'pass' })
  })

  it('未取得のガード対象パスは ensure（fail-open にしない）', () => {
    expect(decideGate({ path: '/market', isServer: false, publicLoaded: false, enabled: () => true }))
      .toEqual({ action: 'ensure', gateKey: 'MARKET' })
  })

  it('取得済みなら enabled の真偽で pass / deny に分かれる', () => {
    expect(decideGate({ path: '/market', isServer: false, publicLoaded: true, enabled: () => true }))
      .toEqual({ action: 'pass' })
    expect(decideGate({ path: '/market', isServer: false, publicLoaded: true, enabled: () => false }))
      .toEqual({ action: 'deny', gateKey: 'MARKET' })
  })
})
