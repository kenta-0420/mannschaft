// 固定実機試練。自律3住民探索の代用にはしない。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import type { ModerationManifest } from './village-moderation-fixture.ts'

test.use({ trace: 'off', video: 'off' })
const output = path.resolve('build/village-history-real')
const env = (name: string) => { const value = process.env[name]; if (!value) throw new Error('MODERATION_ENV_MISSING'); return value }
type Phase = 'INIT' | 'LOGIN_WAIT' | 'ME_WAIT' | 'ROLE_VIEW_WAIT' | 'ROLE_VIEW_DONE'
  | 'ROLE_CHANGE_WAIT' | 'ROLE_CHANGE_DONE' | 'HEADMAN_API_PRECONDITION' | 'HEADMAN_UI_COUNTERPART'
  | 'BAN_WAIT' | 'BANNED_UI_WAIT' | 'UI_COMPLETE'
const progress = { phase: 'INIT' as Phase, actorIndex: null as number | null,
  canonicalLoginSuccesses: 0, meStatus: null as number | null,
  uiStep: 'AUTH' as 'AUTH' | 'MEMBERS' | 'REVIEW' | 'ROLE_CHANGE' | 'SETUP_ONLY' | 'BAN' | 'MEMBERS_DENIED' | 'COMPLETE' }
const write = (name: string, value: unknown) => {
  mkdirSync(output, { recursive: true })
  writeFileSync(path.join(output, name), JSON.stringify(value, null, 2) + '\n')
}
function phase(value: Phase, actorIndex: number | null, step: typeof progress.uiStep) {
  progress.phase = value; progress.actorIndex = actorIndex; progress.uiStep = step
  write('moderation-progress-safe.json', progress)
}
async function observe(page: Page, pathname: string, action: () => Promise<unknown>, method = 'GET') {
  const received = page.waitForResponse((response) => new URL(response.url()).pathname === pathname
    && response.request().method() === method)
  await action()
  return received
}
async function capture(page: Page, name: string) {
  const screen = page.locator('section').filter({ has: page.getByRole('heading', { name: '村人一覧', exact: true }) })
  await expect(screen).toHaveCount(1)
  await expect(screen).toBeVisible()
  const dest = path.join(output, 'safe-business')
  mkdirSync(dest, { recursive: true })
  await screen.screenshot({ path: path.join(dest, `moderation-${name}.png`) })
  writeFileSync(path.join(dest, `moderation-${name}.dom.html`), await screen.innerHTML(), { encoding: 'utf8', flag: 'wx' })
}

test('村の三役割表示とBAN済HEADMANの管理導線拒否を真のセッションで確認する', async ({ browser }) => {
  const fixture = JSON.parse(readFileSync(env('VILLAGE_MODERATION_FIXTURE_MANIFEST'), 'utf8')) as ModerationManifest
  expect(fixture.actors.map((a) => a.role)).toEqual(['HEADMAN','ELDER','VILLAGER'])
  expect(new Set(fixture.actors.map((a) => a.userId)).size).toBe(3)
  const contexts: BrowserContext[] = []
  const pages: Page[] = []
  const observations: { actorIndex: number; role: string; membersStatus: number; roleButtons: number; banButtons: number; reviewerTable: boolean }[] = []
  const base = `/api/v1/villages/${fixture.villageId}`
  try {
    for (const [index, actor] of fixture.actors.entries()) {
      const context = await browser.newContext({ baseURL: env('BASE_URL'), storageState: { cookies: [], origins: [] },
        locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: { width: 1280, height: 800 } })
      contexts.push(context)
      const page = await context.newPage(); pages.push(page)
      phase('LOGIN_WAIT', index, 'AUTH')
      try { await loginViaApi(page, { email: env(`${actor.credentialEnvPrefix}_EMAIL`),
        password: env(`${actor.credentialEnvPrefix}_PASSWORD`) }, { apiBaseUrl: env('API_BASE_URL') }) }
      catch { throw new Error('MODERATION_CANONICAL_LOGIN_FAILED') }
      phase('ME_WAIT', index, 'AUTH')
      const me = await page.request.get(`${env('API_BASE_URL')}/api/v1/users/me`)
      progress.meStatus = me.status()
      expect(me.status()).toBe(200)
      const principal = (await me.json()).data
      expect(Number(principal.id)).toBe(actor.userId)
      expect(principal.systemRole).not.toBe('SYSTEM_ADMIN')
      progress.canonicalLoginSuccesses++
      phase('ROLE_VIEW_WAIT', index, 'MEMBERS')
      const detail = page.waitForResponse((r) => new URL(r.url()).pathname === base && r.request().method() === 'GET')
      const response = await observe(page, `${base}/memberships`, () => page.goto(`/villages/${fixture.villageId}/members`))
      expect(response.status()).toBe(200)
      const village = (await (await detail).json()).data
      expect(village.myRole).toBe(actor.role)
      const roleButtons = page.getByRole('button', { name: 'ロール変更', exact: true })
      const banButtons = page.getByRole('button', { name: 'BANする', exact: true })
      await expect(roleButtons).toHaveCount(index === 0 ? 2 : 0)
      await expect(banButtons).toHaveCount(index === 0 ? 2 : 0)
      const ownRow = page.getByRole('row').filter({ has: page.getByRole('button', { name: `#${actor.userId}`, exact: true }) })
      await expect(ownRow).toHaveCount(1)
      await expect(ownRow.getByText(['村長','長老','村人'][index], { exact: true })).toBeVisible()
      await capture(page, `initial-${index}`)
      if (index < 2) {
        const review = await observe(page, `${base}/join-requests`, () => page.goto(`/villages/${fixture.villageId}/join-request`))
        expect(review.status()).toBe(200)
        await expect(page.getByTestId('join-request-review-table')).toBeVisible()
      } else {
        await page.goto(`/villages/${fixture.villageId}/join-request`)
        await expect(page.getByText('すでに村人です', { exact: true })).toBeVisible()
        await expect(page.getByTestId('join-request-review-table')).toHaveCount(0)
      }
      observations.push({ actorIndex: index, role: actor.role, membersStatus: response.status(),
        roleButtons: index === 0 ? 2 : 0, banButtons: index === 0 ? 2 : 0, reviewerTable: index < 2 })
      phase('ROLE_VIEW_DONE', index, 'REVIEW')
    }
    const [headman, target] = pages
    const uiRoleTransitions: { membershipId: string; role: string; status: number }[] = []
    const membersResponse = await observe(headman, `${base}/memberships`,
      () => headman.goto(`/villages/${fixture.villageId}/members`))
    expect(membersResponse.status()).toBe(200)
    // Dの正規Dialogを使いFを長老にし、村人へ戻す。APIの代替更新をしない。
    const villagerRow = headman.getByRole('row').filter({ has: headman.getByRole('button', {
      name: `#${fixture.actors[2].userId}`, exact: true }) })
    for (const [role, label] of [['ELDER', '長老'], ['VILLAGER', '村人']] as const) {
      phase('ROLE_CHANGE_WAIT', 0, 'ROLE_CHANGE')
      await expect(villagerRow).toHaveCount(1)
      await villagerRow.getByRole('button', { name: 'ロール変更', exact: true }).click()
      const roleDialog = headman.getByRole('dialog', { name: 'ロール変更', exact: true })
      await expect(roleDialog).toBeVisible()
      await roleDialog.getByRole('combobox').click()
      await headman.getByRole('option', { name: label, exact: true }).click()
      const changed = await observe(headman, `${base}/memberships/${fixture.actors[2].membershipId}/role`,
        () => roleDialog.getByRole('button', { name: '保存', exact: true }).click(), 'PATCH')
      expect(changed.status()).toBe(200)
      const member = (await changed.json()).data
      expect(member).toMatchObject({ id: fixture.actors[2].membershipId,
        subjectId: fixture.actors[2].userId, role })
      await expect(roleDialog).toBeHidden()
      await expect(villagerRow.getByText(label, { exact: true })).toBeVisible()
      uiRoleTransitions.push({ membershipId: member.id, role: member.role, status: changed.status() })
      phase('ROLE_CHANGE_DONE', 0, 'ROLE_CHANGE')
      await capture(headman, `role-${role.toLowerCase()}`)
    }
    // UIにはHEADMAN選択肢がない。正規APIによるfixture前提でありUI昇格合格ではない。
    phase('HEADMAN_API_PRECONDITION', 0, 'SETUP_ONLY')
    const promoted = await headman.request.patch(`${env('API_BASE_URL')}${base}/memberships/${fixture.actors[1].membershipId}/role`, { data: { role: 'HEADMAN' } })
    expect(promoted.status()).toBe(200)
    expect((await promoted.json()).data).toMatchObject({ id: fixture.actors[1].membershipId, role: 'HEADMAN' })
    phase('HEADMAN_UI_COUNTERPART', 1, 'MEMBERS')
    const counterpart = await observe(target, `${base}/memberships`, () => target.goto(`/villages/${fixture.villageId}/members`))
    expect(counterpart.status()).toBe(200)
    await expect(target.getByRole('button', { name: 'ロール変更', exact: true })).toHaveCount(2)
    await expect(target.getByRole('button', { name: 'BANする', exact: true })).toHaveCount(2)
    await expect(target.getByRole('row').filter({ has: target.getByRole('button', {
      name: `#${fixture.actors[1].userId}`, exact: true }) }).getByText('村長', { exact: true })).toBeVisible()
    await capture(target, 'headman-counterpart')
    await observe(headman, `${base}/memberships`, () => headman.goto(`/villages/${fixture.villageId}/members`))
    const row = headman.getByRole('row').filter({ has: headman.getByRole('button', { name: `#${fixture.actors[1].userId}`, exact: true }) })
    await expect(row).toHaveCount(1)
    await row.getByRole('button', { name: 'BANする', exact: true }).click()
    const dialog = headman.getByRole('dialog', { name: 'BANする', exact: true })
    await expect(dialog).toBeVisible()
    await dialog.locator('#ban-reason').fill(fixture.banReason)
    phase('BAN_WAIT', 0, 'BAN')
    const banned = await observe(headman, `${base}/memberships/${fixture.actors[1].membershipId}/ban`,
      () => dialog.getByRole('button', { name: 'BANする', exact: true }).click(), 'POST')
    expect(banned.status()).toBe(200)
    const result = (await banned.json()).data
    expect(result).toMatchObject({ id: fixture.actors[1].membershipId, role: 'HEADMAN', isBanned: true })
    // leftAtはDTOにない。後段の実DB witnessで非NULLと許可差分を確認する。
    await expect(row).toHaveCount(0)
    for (const index of [0, 2]) await expect(headman.getByRole('button', {
      name: `#${fixture.actors[index].userId}`, exact: true })).toBeVisible()
    await capture(headman, 'ban-active-list-exclusion')
    phase('BANNED_UI_WAIT', 1, 'MEMBERS_DENIED')
    // 同じtarget contextの実ページ再読込。業務GETをAPIで代替しない。
    const publicDetail = target.waitForResponse((r) => new URL(r.url()).pathname === base && r.request().method() === 'GET')
    const denied = await observe(target, `${base}/memberships`, () => target.reload())
    const publicResponse = await publicDetail
    expect(publicResponse.status()).toBe(200)
    expect((await publicResponse.json()).data).toMatchObject({ isMember: false, myRole: null })
    expect(denied.status()).toBe(404)
    expect((await denied.json()).error.code).toBe('VILLAGE_007')
    await expect(target.getByRole('button', { name: 'ロール変更', exact: true })).toHaveCount(0)
    await expect(target.getByRole('button', { name: 'BANする', exact: true })).toHaveCount(0)
    await capture(target, 'banned-management-denied')
    await target.goto(`/villages/${fixture.villageId}/join-request`)
    await expect(target.getByTestId('join-request-review-table')).toHaveCount(0)
    await expect(target.getByTestId('join-request-message')).toBeVisible()
    write('moderation-observations-safe.json', { observations, uiRoleTransitions,
      headmanPromotion: 'NORMAL_API_SETUP_ONLY',
      headmanCounterpartObserved: true, uiBanObserved: true, sameTargetSession: true,
      bannedMemberReadStatus: denied.status(), publicVillageDetailStatus: publicResponse.status(),
      reviewerTableAbsent: true, autonomousExploration: 'HOLD' })
    phase('UI_COMPLETE', null, 'COMPLETE')
  } finally {
    const cleanup = await Promise.all(contexts.map(async (context, index) => {
      let logoutStatus: number | null = null, authCookiesAbsent = false, closed = false
      try { logoutStatus = (await context.request.post(`${env('API_BASE_URL')}/api/v1/auth/logout`)).status() } catch { /* 全所有contextへ続行 */ }
      try { authCookiesAbsent = !(await context.cookies()).some((c) => ['access_token','refresh_token'].includes(c.name)) } catch { /* 未証明 */ }
      try { await context.close(); closed = true } catch { /* 未証明 */ }
      return { actorIndex: index, logoutStatus, authCookiesAbsent, closed }
    }))
    write('moderation-session-cleanup.json', { uiContexts: contexts.length,
      uiLogoutSuccesses: cleanup.filter((c) => c.logoutStatus === 200).length,
      uiContextsClosed: cleanup.filter((c) => c.closed).length, observations: cleanup })
    expect(cleanup.length === 3 && cleanup.every((c) => c.logoutStatus === 200 && c.authCookiesAbsent && c.closed)).toBe(true)
  }
})
