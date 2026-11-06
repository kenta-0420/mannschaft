import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

export interface FriendTeam { id: number; slug: string; name: string }
export interface FriendActor { page: Page; context: BrowserContext; userId: number }
export const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'

/** 専用ユーザーを一度だけログインさせる。対象操作のAPI代替は提供しない。 */
export async function openFriendActor(browser: Browser, role: 'SOURCE' | 'TARGET' | 'MEMBER'): Promise<FriendActor> {
  const email = process.env['FRIEND_' + role + '_EMAIL']
  const password = process.env.TEST_USER_PASSWORD
  if (!email || !password) throw new Error('専用実機ユーザーの環境変数が未設定: FRIEND_' + role + '_EMAIL / TEST_USER_PASSWORD')
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] }, viewport: role === 'TARGET' ? { width: 390, height: 844 } : { width: 1280, height: 900 }, isMobile: role === 'TARGET', hasTouch: role === 'TARGET' })
  const page = await context.newPage()
  // 静的robots経路で認証を復元し、gotoのたびに古いlocaleを戻すinit scriptを残さない。
  await loginViaApi(page, { email, password }, { apiBaseUrl: API_BASE, deferNavigation: false })
  const me = await page.request.get(API_BASE + '/api/v1/users/me')
  expect(me.status()).toBe(200)
  const profile = (await me.json()).data as { id: number; locale?: string | null }
  if (profile.locale !== 'ja') {
    // 保存済みlocaleの基準化も実UIで行う。追加ログインやAPIによるlocale更新はしない。
    await page.goto('/settings/language', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.getByRole('combobox').first().click()
    await page.getByRole('option', { name: '日本語', exact: true }).click()
    const saved = page.waitForResponse(response => response.request().method() === 'PUT' && new URL(response.url()).pathname === '/api/v1/users/me')
    await page.locator('button[translate="no"]').click()
    expect((await saved).status(), '日本語baselineを実UIで保存').toBe(200)
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeVisible()
    const actual = await page.request.get(API_BASE + '/api/v1/users/me')
    expect(actual.status()).toBe(200)
    expect((await actual.json()).data.locale, '保存後の本人localeがjaであること').toBe('ja')
  }
  return { page, context, userId: profile.id }
}

/** 所有チームのIDだけを保持し、失敗時も作成済み分を後始末する。 */
export class FriendRequestFixture {
  private owned: { actor: FriendActor; team: FriendTeam }[] = []
  private sequence = 0
  private readonly suffix = Date.now().toString(36)

  async team(actor: FriendActor, label: string, visibility = 'PUBLIC'): Promise<FriendTeam> {
    const slug = ('fr-' + this.suffix + '-' + this.sequence++).slice(0, 30)
    const name = '実機フレンド ' + label + ' ' + slug
    const response = await actor.page.request.post(API_BASE + '/api/v1/teams', { data: { slug, name, visibility } })
    expect(response.status(), '前提チーム作成').toBe(201)
    const data = (await response.json()).data as { numericId?: number; id: number | string; slug: string }
    const id = data.numericId ?? (typeof data.id === 'number' ? data.id : Number.NaN)
    expect(Number.isFinite(id), '数値チームIDが返ること').toBe(true)
    const team = { id, slug: data.slug, name }
    this.owned.push({ actor, team })
    return team
  }

  async join(owner: FriendActor, member: FriendActor, team: FriendTeam): Promise<void> {
    const invite = await owner.page.request.post(API_BASE + '/api/v1/teams/' + team.slug + '/invite-tokens', { data: { roleId: 4, expiresIn: '1d', maxUses: 1 } })
    expect(invite.status(), '前提のMEMBER招待').toBe(201)
    const token = (await invite.json()).data.token as string
    const joined = await member.page.request.post(API_BASE + '/api/v1/invite/' + token + '/join')
    expect(joined.status(), '前提のMEMBER参加').toBe(200)
  }

  async delegate(owner: FriendActor, member: FriendActor, team: FriendTeam): Promise<void> {
    const catalog = await owner.page.request.get(API_BASE + '/api/v1/permissions?scopeType=TEAM&scopeId=' + team.id)
    expect(catalog.status()).toBe(200)
    const permissions = (await catalog.json()).data as { id: number; name: string }[]
    const permission = permissions.find(p => p.name === 'MANAGE_FRIEND_TEAMS')
    expect(permission).toBeDefined()
    const created = await owner.page.request.post(API_BASE + '/api/v1/teams/' + team.slug + '/permission-groups', { data: { name: '実機フレンド管理', targetRole: 'MEMBER', permissionIds: [permission!.id] } })
    expect(created.status()).toBe(201)
    const group = (await created.json()).data as { id: number }
    const assigned = await owner.page.request.put(API_BASE + '/api/v1/teams/' + team.slug + '/members/' + member.userId + '/permission-groups', { data: { groupIds: [group.id] } })
    expect(assigned.status()).toBe(200)
  }

  async revoke(owner: FriendActor, member: FriendActor, team: FriendTeam): Promise<void> {
    const response = await owner.page.request.put(API_BASE + '/api/v1/teams/' + team.slug + '/members/' + member.userId + '/permission-groups', { data: { groupIds: [] } })
    expect(response.status()).toBe(200)
  }

  ownedTeams(): FriendTeam[] { return this.owned.map(({ team }) => team) }

  async cleanup(): Promise<void> {
    const failures: number[] = []
    for (const { actor, team } of [...this.owned].reverse()) {
      const response = await actor.page.request.delete(API_BASE + '/api/v1/teams/' + team.slug)
      if (![200, 204, 404].includes(response.status())) failures.push(response.status())
    }
    expect(failures, '所有チームの後始末失敗HTTP').toEqual([])
  }
}
