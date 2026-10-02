/**
 * ScopeTabBar.vue — items computed のソートロジック ユニットテスト
 *
 * PR #1759 で修正した「store.tabOrders[scopeType] の sortOrder 昇順でタブを並べ替える」
 * バグ修正（tabOrders が存在するとき rawItems を sortOrder 昇順にソートする）を固める。
 *
 * コンポーネント全体のマウントには Pinia + composables + Nuxt auto-import の環境が必要で
 * vitest-environment:nuxt でも全依存を解決するのが困難なため、ソートロジックを pure 関数として
 * 抜き出してテストする方針とした。
 *
 * ScopeTabBar.vue (L27-37) のロジック:
 *   const items = computed(() => {
 *     const rawItems = page.value?.items ?? []
 *     const orders = store.tabOrders[props.scopeType]
 *     if (!orders || orders.length === 0) return rawItems
 *     const orderMap = new Map(orders.map(o => [o.scopeId, o.sortOrder]))
 *     return [...rawItems].sort((a, b) => {
 *       const aOrder = orderMap.get(a.scopeId) ?? Infinity
 *       const bOrder = orderMap.get(b.scopeId) ?? Infinity
 *       return aOrder - bOrder
 *     })
 *   })
 */

import { describe, it, expect } from 'vitest'

// ---------- ソートロジックを抜き出した pure 関数（ScopeTabBar.vue と同一ロジック） ----------

interface RawItem {
  scopeId: string
  name?: string
  [key: string]: unknown
}

interface TabOrderEntry {
  scopeId: string
  sortOrder: number
}

/**
 * ScopeTabBar.vue の items computed と等価なソート関数。
 * rawItems を tabOrders の sortOrder 昇順で並べ替える。
 * tabOrders が空の場合は rawItems をそのまま返す。
 */
function sortItemsByTabOrders(rawItems: RawItem[], orders: TabOrderEntry[]): RawItem[] {
  if (!orders || orders.length === 0) return rawItems
  const orderMap = new Map(orders.map((o) => [o.scopeId, o.sortOrder]))
  return [...rawItems].sort((a, b) => {
    const aOrder = orderMap.get(a.scopeId) ?? Infinity
    const bOrder = orderMap.get(b.scopeId) ?? Infinity
    return aOrder - bOrder
  })
}

// ---------- テスト ----------

describe('ScopeTabBar items ソートロジック（PR #1759 バグ修正）', () => {
  const rawItems: RawItem[] = [
    { scopeId: 'team-001', name: 'チームA' },
    { scopeId: 'team-002', name: 'チームB' },
    { scopeId: 'team-003', name: 'チームC' },
  ]

  it('tabOrders が空のとき rawItems をそのまま返す', () => {
    const result = sortItemsByTabOrders(rawItems, [])
    expect(result).toEqual(rawItems)
  })

  it('tabOrders が存在するとき sortOrder 昇順でソートされる', () => {
    const orders: TabOrderEntry[] = [
      { scopeId: 'team-001', sortOrder: 3 },
      { scopeId: 'team-002', sortOrder: 1 },
      { scopeId: 'team-003', sortOrder: 2 },
    ]
    const result = sortItemsByTabOrders(rawItems, orders)
    expect(result.map((i) => i.scopeId)).toEqual(['team-002', 'team-003', 'team-001'])
  })

  it('tabOrders に含まれない scopeId は末尾（Infinity）に押しやられる', () => {
    const orders: TabOrderEntry[] = [
      { scopeId: 'team-003', sortOrder: 1 },
      // team-001, team-002 はエントリなし → Infinity 扱い → 末尾
    ]
    const result = sortItemsByTabOrders(rawItems, orders)
    expect(result).toHaveLength(rawItems.length)
    expect(result[0]?.scopeId).toBe('team-003')
    // team-001 / team-002 は順不同で末尾に来ることを確認
    const rest = result.slice(1).map((i) => i.scopeId)
    expect(rest).toContain('team-001')
    expect(rest).toContain('team-002')
  })

  it('rawItems を破壊的変更しない（スプレッドで新配列を返す）', () => {
    const orders: TabOrderEntry[] = [
      { scopeId: 'team-001', sortOrder: 3 },
      { scopeId: 'team-002', sortOrder: 1 },
      { scopeId: 'team-003', sortOrder: 2 },
    ]
    const original = [...rawItems]
    sortItemsByTabOrders(rawItems, orders)
    // rawItems の順序が変わっていないこと
    expect(rawItems.map((i) => i.scopeId)).toEqual(original.map((i) => i.scopeId))
  })

  it('全アイテムが同一 sortOrder のとき元の相対順を維持する（stable sort）', () => {
    const orders: TabOrderEntry[] = [
      { scopeId: 'team-001', sortOrder: 1 },
      { scopeId: 'team-002', sortOrder: 1 },
      { scopeId: 'team-003', sortOrder: 1 },
    ]
    const result = sortItemsByTabOrders(rawItems, orders)
    // sort は stable なので元の並び順が維持されることを期待
    expect(result.map((i) => i.scopeId)).toEqual(['team-001', 'team-002', 'team-003'])
  })
})
