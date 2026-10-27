// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { springPage } from '../../tests/fixtures/spring-page'

/** 全Controller Pageメソッドとの27=27照合済み。要素DTO全体や認可の合格を意味しない。 */
const AUDITED_ENDPOINTS = {
  "/api/v1/admin/dashboard/users": "未使用宣言",
  "/api/v1/admin/village-creation-requests": "content direct",
  "/api/v1/committees/{committeeId}/distributions": "adapter",
  "/api/v1/me/reflections/archive/search": "content direct",
  "/api/v1/organizations/{orgId}/committees": "adapter",
  "/api/v1/organizations/{orgId}/point-cards/balance-events": "content direct",
  "/api/v1/organizations/{orgId}/point-cards/stamps": "content direct",
  "/api/v1/organizations/{orgId}/succession/covenants": "FE未使用",
  "/api/v1/organizations/{organizationId}/join-requests": "content direct",
  "/api/v1/public/blog-posts/{postId}/comments": "公開direct",
  "/api/v1/public/organizations/search": "公開direct",
  "/api/v1/public/organizations/{slug}/events": "公開direct",
  "/api/v1/public/organizations/{slug}/posts": "公開direct",
  "/api/v1/public/organizations/{slug}/timeline-posts": "公開direct",
  "/api/v1/public/teams/search": "公開direct",
  "/api/v1/public/teams/{teamId}/events": "公開direct",
  "/api/v1/public/teams/{teamId}/posts": "公開direct",
  "/api/v1/public/teams/{teamId}/timeline-posts": "公開direct",
  "/api/v1/public/users/{userId}/posts": "公開direct",
  "/api/v1/system-admin/dashboard/organizations": "content direct",
  "/api/v1/system-admin/dashboard/teams": "未使用宣言",
  "/api/v1/system-admin/dashboard/users": "adapter",
  "/api/v1/system-admin/email-outbox": "adapter",
  "/api/v1/system-admin/gdpr/purge-status": "content direct",
  "/api/v1/teams/{id}/friend-notifications": "FE未使用",
  "/api/v1/teams/{teamId}/join-requests": "content direct",
  "/api/v1/villages/{villageId}/join-requests": "content direct"
}
interface Schema { $ref?: string; properties?: Record<string, Schema> }
interface Spec {
  components: { schemas: Record<string, Schema> }
  paths: Record<string, { get?: { responses?: Record<string, { content?: Record<string, { schema?: Schema }> }> } }>
}
const spec = JSON.parse(readFileSync(resolve(__dirname, '../../../docs/openapi.json'), 'utf8')) as Spec
const PAGE_KEYS = Object.keys(springPage([])).sort()

/** OpenAPIの直返し/DATA内Pageをそれぞれ1段だけ参照する。URL式やTSソースは解析しない。 */
function pageEndpoints(input: Spec): string[] {
  const deref = (s: Schema | undefined): Schema | undefined => s?.$ref ? input.components.schemas[s.$ref.split('/').at(-1)!] : s
  return Object.entries(input.paths).flatMap(([path, operations]) => {
    const outer = deref(operations.get?.responses?.['200']?.content?.['application/json']?.schema)
    const page = deref(outer?.properties?.data) ?? outer
    return JSON.stringify(Object.keys(page?.properties ?? {}).sort()) === JSON.stringify(PAGE_KEYS) ? [path] : []
  }).sort()
}
function assertCoverage(actual: string[], audited: string[]) {
  expect(actual).toEqual([...audited].sort())
}

describe('Spring Page公開経路の監査coverage', () => {
  it('Spring Page27経路が登録集合と完全一致し、独自Page DTO7経路を混ぜない', () => {
    expect(Object.keys(AUDITED_ENDPOINTS)).toHaveLength(27)
    assertCoverage(pageEndpoints(spec), Object.keys(AUDITED_ENDPOINTS))
  })
  it('OpenAPIへ未登録の新Page経路を加えた負例を必ず拒否する', () => {
    const added: Spec = { ...spec, paths: { ...spec.paths, '/api/v1/new-page': spec.paths['/api/v1/system-admin/email-outbox']! } }
    expect(() => assertCoverage(pageEndpoints(added), Object.keys(AUDITED_ENDPOINTS))).toThrow()
  })
  it('adapter4・未使用宣言2・FE未使用2を区別して残す', () => {
    const modes = Object.values(AUDITED_ENDPOINTS)
    expect(modes.filter(m => m === 'adapter')).toHaveLength(4)
    expect(modes.filter(m => m === '未使用宣言')).toHaveLength(2)
    expect(modes.filter(m => m === 'FE未使用')).toHaveLength(2)
  })
})
