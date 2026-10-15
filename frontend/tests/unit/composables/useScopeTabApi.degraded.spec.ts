/**
 * F10.1.1 管理者向け admin-action-required の degraded 正規化（検分🟠・二重防御）単体テスト。
 *
 * useScopeTabApi.ts の toAdminActionRequiredSummary はモジュール内 private 関数のため、
 * その degraded 正規化ロジック（degraded ドメインは pendingCount=0・totalPending は正規化後合計で再計算）
 * を本テストでリプロダクションし、不変条件を固定する。
 *
 * 期待する不変条件:
 *  - degraded=true のドメインは pendingCount が 0 に正規化される（集計失敗を件数と混同しない）
 *  - totalPending は BE 値を鵜呑みにせず、正規化後の各ドメイン件数（degraded=0）の合計で再計算される
 *  - 万一 BE が degraded 分を total / pending_count に含めて返しても FE 側で混入を防ぐ
 */
import { describe, it, expect } from 'vitest'

interface RawDomain {
  domain: string
  pending_count: number
  degraded: boolean
}

/** toAdminActionRequiredSummary の degraded 正規化部分の再現。 */
function normalize(raw: { total_pending: number; domains: RawDomain[] }) {
  const domains = (raw.domains ?? []).map((d) => {
    const degraded = d.degraded ?? false
    return {
      domain: d.domain,
      pendingCount: degraded ? 0 : (d.pending_count ?? 0),
      degraded,
    }
  })
  const totalPending = domains.reduce((sum, d) => sum + d.pendingCount, 0)
  return { totalPending, domains }
}

describe('toAdminActionRequiredSummary degraded 正規化', () => {
  it('degraded ドメインは pendingCount=0 に正規化され total に加算されない', () => {
    const r = normalize({
      total_pending: 5, // BE 値（healthy 分のみ加算済みの想定）
      domains: [
        { domain: 'RESERVATION_APPROVAL', pending_count: 3, degraded: false },
        { domain: 'SHIFT_REQUEST', pending_count: 2, degraded: false },
        { domain: 'MATCHING_APPLICATION', pending_count: 0, degraded: true },
      ],
    })

    const degradedDomain = r.domains.find(d => d.domain === 'MATCHING_APPLICATION')
    expect(degradedDomain?.pendingCount).toBe(0)
    expect(degradedDomain?.degraded).toBe(true)
    // healthy 2 ドメインの合計のみ
    expect(r.totalPending).toBe(5)
  })

  it('BE が degraded 分を pending_count / total に含めて返しても FE で混入させない（二重防御）', () => {
    const r = normalize({
      total_pending: 100, // BE が degraded 分も含めて誤って返したケース
      domains: [
        { domain: 'RESERVATION_APPROVAL', pending_count: 4, degraded: false },
        // degraded なのに件数が乗っている（BE 不具合の想定）
        { domain: 'SHIFT_REQUEST', pending_count: 96, degraded: true },
      ],
    })

    // degraded は 0 に潰す
    expect(r.domains.find(d => d.domain === 'SHIFT_REQUEST')?.pendingCount).toBe(0)
    // total も BE の 100 ではなく healthy 分（4）で再計算
    expect(r.totalPending).toBe(4)
  })

  it('全ドメイン healthy なら BE 値と一致する', () => {
    const r = normalize({
      total_pending: 7,
      domains: [
        { domain: 'RESERVATION_APPROVAL', pending_count: 3, degraded: false },
        { domain: 'SHIFT_REQUEST', pending_count: 4, degraded: false },
      ],
    })
    expect(r.totalPending).toBe(7)
  })

  it('全ドメイン degraded なら totalPending=0', () => {
    const r = normalize({
      total_pending: 50,
      domains: [
        { domain: 'RESERVATION_APPROVAL', pending_count: 20, degraded: true },
        { domain: 'SHIFT_REQUEST', pending_count: 30, degraded: true },
      ],
    })
    expect(r.totalPending).toBe(0)
  })
})
