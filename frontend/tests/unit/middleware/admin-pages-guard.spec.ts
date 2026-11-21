import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

/**
 * CMP-261012-2343: `pages/admin/` 配下の FE ガード網羅の番人。
 *
 * `/system-admin` 配下は CMP-260930-1933 で `['auth', 'system-admin']` に揃えたが、`/admin` 配下は未調査だった。
 * 全ページを「想定ロール」で分類し、分類の漏れ（新規ページの追加忘れ）と、
 * SYSTEM_ADMIN 専用ページのミドルウェア欠落を機械的に検出する。
 * 根拠となる BE 認可と分類表は docs/security/admin_pages_fe_guard_inventory.md を参照。
 *
 * これは UX（誤遷移の早期遮断）の番人であり、認可の最終判断は BE が担う。
 */

/** BE が SYSTEM_ADMIN 限定で守る API だけを呼ぶページ（`/api/v1/system-admin/**` または SecurityConfig の hasRole 規則、Service 層 checkSystemAdmin）。 */
const SYSTEM_ADMIN_PAGES = [
  'ad-credit-limit-requests.vue',
  'ad-rate-cards.vue',
  'advertiser-accounts.vue',
  'affiliate-settings.vue',
  'announcements.vue',
  'appeals.vue',
  'audit-logs.vue',
  'campaigns.vue',
  'feature-flags.vue',
  'maintenance.vue',
  'moderation.vue',
  'module-pricing.vue',
  'modules.vue',
  'organizations.vue',
  'packages.vue',
  'point-card-synonyms.vue',
  'reports.vue',
  'seasonal-wallpapers.vue',
  'storage-plans.vue',
  'tax-settings.vue',
  'templates.vue',
  'users.vue',
  'villages/creation-requests.vue',
]

/** スコープ（チーム/組織）の ADMIN 系ロールが対象で、ページ内に入口ガード（useAdminScopeGuard）を持つページ。 */
const SCOPE_GUARDED_PAGES = [
  'bulletin-categories.vue',
  'line-settings.vue',
  'receipt-settings.vue',
  'receipts.vue',
  'schedule-settings.vue',
  'sns-settings.vue',
  'vendors/index.vue',
]

/**
 * スコープ ADMIN 系だが入口ガードが無いページ（要判断。最低ロール・個人スコープの扱いが未決のため未実装）。
 * shift-budget 4ページは useShiftBudgetAdminAccess で導線を出し分けるだけで、URL 直打ちの遷移は止めない。
 * ガードを実装したら、このリストから SCOPE_GUARDED_PAGES へ移すこと（移し忘れは下の検査が赤で知らせる）。
 */
const SCOPE_PENDING_DECISION_PAGES = [
  'dashboard.vue',
  'feedbacks.vue',
  'member-permissions.vue',
  'permission-groups.vue',
  'promotions.vue',
  'proxy-desk.vue',
  'proxy/consents.vue',
  'proxy/records.vue',
  'shift-budget/alerts.vue',
  'shift-budget/allocations.vue',
  'shift-budget/dashboard.vue',
  'shift-budget/failed-events.vue',
]

const SCOPE_ADMIN_PAGES = [...SCOPE_GUARDED_PAGES, ...SCOPE_PENDING_DECISION_PAGES]

const ROOT = join(process.cwd(), 'app', 'pages', 'admin')

function collectVueFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    return statSync(full).isDirectory() ? collectVueFiles(full) : full.endsWith('.vue') ? [full] : []
  })
}

const allPages = collectVueFiles(ROOT)
  .map((f) => relative(ROOT, f).split('\\').join('/'))
  .sort()

describe('admin 配下ページのガード網羅（番人）', () => {
  it('全ページが想定ロールで分類されている（新規ページは分類表への追加が必須）', () => {
    const classified = new Set([...SYSTEM_ADMIN_PAGES, ...SCOPE_ADMIN_PAGES])
    expect(allPages.filter((p) => !classified.has(p))).toEqual([])
    expect([...classified].filter((p) => !allPages.includes(p))).toEqual([])
  })

  it("SYSTEM_ADMIN 専用ページは middleware: ['auth', 'system-admin'] を指定している", () => {
    const unguarded = SYSTEM_ADMIN_PAGES.filter(
      (p) => !/middleware:\s*\[\s*'auth'\s*,\s*'system-admin'\s*\]/.test(readFileSync(join(ROOT, p), 'utf8')),
    )
    expect(unguarded).toEqual([])
  })

  it('全ページが auth ミドルウェアを含む', () => {
    const missing = allPages.filter(
      (p) => !/middleware:\s*(\[[^\]]*)?'auth'/.test(readFileSync(join(ROOT, p), 'utf8')),
    )
    expect(missing).toEqual([])
  })

  it('SYSTEM_ADMIN 専用ページとスコープ系ページの分類が重複していない', () => {
    expect(SYSTEM_ADMIN_PAGES.filter((p) => SCOPE_ADMIN_PAGES.includes(p))).toEqual([])
    expect(SCOPE_GUARDED_PAGES.filter((p) => SCOPE_PENDING_DECISION_PAGES.includes(p))).toEqual([])
  })

  it('スコープ系の「ガード済み」ページは useAdminScopeGuard を呼んでいる（ガードが外れたら赤）', () => {
    const missing = SCOPE_GUARDED_PAGES.filter(
      (p) => !/useAdminScopeGuard\(/.test(readFileSync(join(ROOT, p), 'utf8')),
    )
    expect(missing).toEqual([])
  })

  it('「要判断」リストのページは入口ガード未実装のまま（実装したらリストを更新する）', () => {
    const nowGuarded = SCOPE_PENDING_DECISION_PAGES.filter((p) =>
      /useAdminScopeGuard\(|middleware:\s*\[[^\]]*'(system-admin|org-role-guard|admin-console)'/.test(
        readFileSync(join(ROOT, p), 'utf8'),
      ),
    )
    expect(nowGuarded).toEqual([])
  })
})
