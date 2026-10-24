import { expect, type APIResponse, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from './auth'

export const API = `${process.env.API_BASE_URL ?? 'http://localhost:8081'}/api/v1`
export const REASON = '仕事のため前半のみ参加します。\n' + '理由の折り返しを確認する長い文章です。'.repeat(20)
export interface AttendanceScope {
  kind: 'teams' | 'organizations'
  slug: string
  scheduleIds: number[]
  inviteIds: number[]
  memberIds: number[]
  title: string
  emptyTitle: string
  outsideTitle: string
}

export async function checked(response: APIResponse): Promise<void> {
  expect(response.ok(), `${response.url()}: ${response.status()} ${await response.text()}`).toBeTruthy()
}
async function data<T>(response: APIResponse): Promise<T> {
  await checked(response)
  return ((await response.json()) as { data: T }).data
}

/** APIは認証・前提作成・後始末のみ。対象の閲覧操作はspecからブラウザで行う。 */
export class AttendanceResultsFixture {
  readonly pages = new Map<string, Page>()
  readonly scopes: AttendanceScope[] = []
  readonly foreignScopes: AttendanceScope[] = []
  readonly userIds = new Map<string, number>()
  readonly names = new Map<string, string>()
  readonly stamp = Date.now().toString(36)
  readonly emails: Record<string, string> = {
    owner: 'e2e-user@test.mannschaft.local',
    partial: 'e2e-dummy-1@test.mannschaft.local',
    absent: 'e2e-dummy-3@test.mannschaft.local',
    undecided: 'e2e-dummy-4@test.mannschaft.local',
    pending: 'e2e-dummy-5@test.mannschaft.local',
    outsider: 'e2e-outsider@test.mannschaft.local',
    foreign: 'e2e-dummy-7@test.mannschaft.local',
  }
  constructor(readonly browser: Browser) {}
  page(role: string): Page {
    const page = this.pages.get(role)
    if (!page) throw new Error(`未作成の実機セッション: ${role}`)
    return page
  }
  async setup(): Promise<void> {
    await this.loginSessions()
    for (const kind of ['teams', 'organizations'] as const) {
      const scope = await this.createScope(kind, 'owner', '出席確認実機')
      for (const role of ['partial', 'absent', 'undecided', 'pending']) await this.join(scope, role)
      await this.schedule(scope, scope.title, 2, true)
      await this.schedule(scope, scope.emptyTitle, 3, true)
      await this.schedule(scope, scope.outsideTitle, 40, true)
      await this.schedule(scope, '出席確認不要の予定', 4, false)
      for (const [role, status, comment] of [
        ['owner', 'ATTENDING', null], ['partial', 'PARTIAL', REASON],
        ['absent', 'ABSENT', '通院のため欠席します。'], ['undecided', 'UNDECIDED', '勤務予定を確認中です。'],
      ] as const) {
        await checked(await this.page(role).request.patch(`${API}/schedules/${scope.scheduleIds[0]}/responses`, { data: { status, comment } }))
      }
      const foreign = await this.createScope(kind, 'foreign', '別所属の出席確認実機')
      await this.schedule(foreign, foreign.title, 2, true)
    }
  }
  async loginSessions(emails = this.emails): Promise<void> {
    // 同一ユーザーの並行ログイン・トークンローテーション競合を避ける。
    for (const [role, email] of Object.entries(emails)) {
      const context = await this.browser.newContext({
        baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
        storageState: { cookies: [], origins: [] }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo',
      })
      const page = await context.newPage()
      this.pages.set(role, page)
      await loginViaApi(page, { email, password: 'TestPass2026!' }, { apiBaseUrl: API.replace('/api/v1', ''), deferNavigation: true })
      const me = await data<{ id: number; lastName: string; systemRole: string | null }>(await page.request.get(`${API}/users/me`))
      expect(me.systemRole, 'SYSTEM_ADMINの認可迂回では成立させない').toBeNull()
      this.userIds.set(role, me.id)
      this.names.set(role, me.lastName)
    }
  }
  async createScope(kind: AttendanceScope['kind'], owner: string, name: string): Promise<AttendanceScope> {
    const slug = `att-${kind === 'teams' ? 't' : 'o'}-${owner === 'owner' ? 'a' : 'b'}-${this.stamp}`
    const created = await data<{ slug: string }>(await this.page(owner).request.post(`${API}/${kind}`, {
      data: { name: `${name}${this.stamp}`, slug, visibility: 'PUBLIC', ...(kind === 'organizations' ? { orgType: 'COMMUNITY' } : {}) },
    }))
    const scope: AttendanceScope = { kind, slug: created.slug, scheduleIds: [], inviteIds: [], memberIds: [], title: `出席確認${kind === 'teams' ? 'チーム' : '組織'}-${this.stamp}`, emptyTitle: `未回答のみ-${this.stamp}`, outsideTitle: `期間外-${this.stamp}` }
    ;(owner === 'foreign' ? this.foreignScopes : this.scopes).push(scope)
    expect(created.slug).toBe(slug)
    const permission = await data<{ roleName: string }>(await this.page(owner).request.get(`${API}/${kind}/${slug}/me/permissions`))
    expect(permission.roleName).toBe('ADMIN')
    return scope
  }
  async join(scope: AttendanceScope, role: string): Promise<void> {
    const invitation = await data<{ id: number; token: string }>(await this.page('owner').request.post(`${API}/${scope.kind}/${scope.slug}/invite-tokens`, {
      data: { roleId: 4, maxUses: 1, expiresIn: '1d' },
    }))
    scope.inviteIds.push(invitation.id)
    await checked(await this.page(role).request.post(`${API}/invite/${invitation.token}/join`, { data: {} }))
    scope.memberIds.push(this.userIds.get(role)!)
    const permission = await data<{ roleName: string }>(await this.page(role).request.get(`${API}/${scope.kind}/${scope.slug}/me/permissions`))
    expect(permission.roleName).toBe('MEMBER')
  }
  async schedule(scope: AttendanceScope, title: string, days: number, required: boolean, targets?: number[]): Promise<void> {
    // ブラウザ利用者と同じAsia/Tokyoの日付軸。正午を使い日跨ぎの曖昧さを避ける。
    const jst = new Date(Date.now() + 9 * 60 * 60 * 1000 + days * 86400000).toISOString().slice(0, 10)
    const owner = this.foreignScopes.includes(scope) ? 'foreign' : 'owner'
    const created = await data<{ id: number }>(await this.page(owner).request.post(`${API}/${scope.kind}/${scope.slug}/schedules`, {
      data: { title, startAt: `${jst}T12:00:00+09:00`, endAt: `${jst}T13:00:00+09:00`, allDay: false, eventType: 'EVENT', visibility: 'MEMBERS_ONLY', attendanceRequired: required, commentOption: 'OPTIONAL', ...(targets ? { targetMode: 'SELECTED_MEMBERS', targetUserIds: targets } : {}) },
    }))
    scope.scheduleIds.push(created.id)
  }
  async cleanup(): Promise<void> {
    const errors: string[] = []
    for (const [owner, scopes] of [['owner', this.scopes], ['foreign', this.foreignScopes]] as const) {
      const page = this.pages.get(owner)
      if (!page) continue
      for (const scope of scopes) {
        for (const id of scope.scheduleIds) {
          const response = await page.request.delete(`${API}/${scope.kind}/${scope.slug}/schedules/${id}?updateScope=THIS_ONLY`)
          if (!response.ok()) errors.push(`予定${id}: ${response.status()} ${await response.text()}`)
        }
        for (const id of scope.inviteIds) {
          const response = await page.request.delete(`${API}/${scope.kind}/${scope.slug}/invite-tokens/${id}`)
          if (!response.ok()) errors.push(`招待${id}: ${response.status()}`)
        }
        for (const id of scope.memberIds) {
          const response = await page.request.delete(`${API}/${scope.kind}/${scope.slug}/members/${id}`)
          if (!response.ok()) errors.push(`所属${id}: ${response.status()}`)
        }
        const response = await page.request.delete(`${API}/${scope.kind}/${scope.slug}`)
        if (!response.ok()) errors.push(`スコープ${scope.slug}: ${response.status()} ${await response.text()}`)
      }
    }
    for (const page of this.pages.values()) await page.context().close()
    expect(errors, '今回所有IDだけを後始末する').toEqual([])
  }
}
