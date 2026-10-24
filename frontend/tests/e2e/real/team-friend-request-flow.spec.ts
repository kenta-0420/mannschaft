import { expect, test, type Page, type Response, type TestInfo } from '@playwright/test'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'
import { FriendRequestFixture, openFriendActor, type FriendActor, type FriendTeam } from './helpers/team-friend-request-fixture'

/** CMP-261024-2051: 対象の申請・返し・取消はすべて実UI。APIモック禁止。 */
test.describe.configure({ mode: 'default' })
test.use({ storageState: { cookies: [], origins: [] } })
let source: FriendActor
let target: FriendActor
let member: FriendActor
let fixture: FriendRequestFixture
let a: FriendTeam
let b: FriendTeam
let candidates: FriendTeam[]

async function open(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
}
async function proof(page: Page, info: TestInfo, name: string): Promise<void> {
  await info.attach(name, { body: await page.screenshot(), contentType: 'image/png' })
  await info.attach(name + '-url', { body: page.url(), contentType: 'text/plain' })
}
function followResponse(page: Page): Promise<Response> {
  return page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/friends/follow'))
}
async function confirmAndSend(page: Page, from: FriendTeam, to: FriendTeam, info: TestInfo): Promise<boolean> {
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByTestId('friend-request-direction')).toContainText(from.slug)
  await expect(dialog.getByTestId('friend-request-direction')).toContainText(to.slug)
  await expect(dialog.locator('textarea')).toHaveCount(0)
  await dialog.getByTestId('friend-request-review').focus()
  await page.keyboard.press('Enter')
  await proof(page, info, 'confirm-' + from.slug + '-to-' + to.slug)
  const response = followResponse(page)
  await dialog.getByTestId('friend-follow-submit').focus()
  await page.keyboard.press('Enter')
  const result = await response
  expect(result.status()).toBe(201)
  expect(result.request().postDataJSON()).toEqual({ targetTeamSlug: to.slug })
  expect(new URL(result.url()).pathname).toBe('/api/v1/teams/' + from.id + '/friends/follow')
  const body = (await result.json()).data as { followerTeamId: number; followedTeamId: number; mutual: boolean }
  expect(body.followerTeamId).toBe(from.id)
  expect(body.followedTeamId).toBe(to.id)
  await info.attach('follow-result', { body: JSON.stringify(body), contentType: 'application/json' })
  await expect(dialog).toHaveCount(0)
  return body.mutual
}
async function tab(page: Page, label: string): Promise<void> {
  await page.getByTestId('friend-list-tabs').getByText(label, { exact: true }).click()
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(240_000)
  const emails = ['SOURCE', 'TARGET', 'MEMBER'].map(r => process.env['FRIEND_' + r + '_EMAIL'])
  expect(new Set(emails).size, 'BrowserContextごとに別ユーザーが必須').toBe(3)
  source = await openFriendActor(browser, 'SOURCE')
  target = await openFriendActor(browser, 'TARGET')
  member = await openFriendActor(browser, 'MEMBER')
  for (const actor of [source, target, member]) {
    const response = await actor.page.request.get((process.env.API_BASE_URL ?? 'http://localhost:8081') + '/api/v1/me/friend-request-source-teams?targetTeamSlug=fr-precondition')
    expect(response.status()).toBe(200)
    expect((await response.json()).pagination.totalElements, '既存seed由来の管理候補が混ざらないこと').toBe(0)
  }
  fixture = new FriendRequestFixture()
  candidates = []
  for (let i = 0; i < 21; i++) candidates.push(await fixture.team(source, '申請元' + i))
  a = candidates[0]!
  b = await fixture.team(target, '受信先')
  await fixture.join(source, member, a)
  await test.info().attach('owned-team-ids', { body: JSON.stringify(fixture.ownedTeams()), contentType: 'application/json' })
})

test.afterAll(async () => {
  try { if (fixture) await fixture.cleanup() }
  finally { for (const actor of [source, target, member]) if (actor) await actor.context.close() }
})

test('FR-01/08/09/12: 管理入口から申請→受信元を確認して返し→相互成立→UI解除が再読込後も残る', async ({}, info) => {
  test.setTimeout(180_000)
  await open(source.page, '/teams/' + a.slug + '/nav')
  await source.page.getByTestId('friend-follow-button').click()
  await expect(source.page.getByTestId('friend-request-fixed-source')).toContainText(a.name)
  await source.page.getByLabel('チーム名で探す').fill(b.name)
  await source.page.getByTestId('friend-follow-candidate').filter({ hasText: b.slug }).click()
  expect(await confirmAndSend(source.page, a, b, info)).toBe(false)
  await tab(source.page, 'フォロー中')
  await expect(source.page.getByTestId('pending-follow-row').filter({ hasText: b.name })).toBeVisible()
  await source.page.reload()
  await tab(source.page, 'フォロー中')
  await expect(source.page.getByTestId('pending-follow-row').filter({ hasText: b.name })).toBeVisible()
  await open(target.page, '/teams/' + b.slug + '/nav')
  await tab(target.page, '届いた申請')
  const incoming = target.page.getByTestId('incoming-follow-row').filter({ hasText: a.slug })
  await expect(incoming).toContainText(a.name + ' → ' + b.name)
  await proof(target.page, info, 'incoming-before-response')
  await incoming.getByTestId('incoming-follow-respond').click()
  expect(await confirmAndSend(target.page, b, a, info)).toBe(true)
  await expect(target.page.getByTestId('incoming-follow-row').filter({ hasText: a.slug })).toHaveCount(0)
  await tab(target.page, 'フレンドチーム')
  await expect(target.page.getByText(a.name, { exact: true })).toBeVisible()
  await open(source.page, '/teams/' + a.slug + '/nav')
  await expect(source.page.getByText(b.name, { exact: true })).toBeVisible()
  await source.page.getByRole('button', { name: '公開設定を切替', exact: true }).click()
  await source.page.getByRole('menuitem', { name: 'フォロー解除' }).click()
  const deletion = source.page.waitForResponse(r => r.request().method() === 'DELETE' && new URL(r.url()).pathname === '/api/v1/teams/' + a.slug + '/friends/follow/' + b.id)
  await source.page.getByRole('alertdialog').locator('button.p-confirmdialog-accept-button').click()
  expect((await deletion).status()).toBe(204)
  await source.page.reload()
  await expect(source.page.getByText(b.name, { exact: true })).toHaveCount(0)
  await proof(source.page, info, 'dissolved-after-reload')
})

test('FR-02/03/08: 公開プロフィールで21候補・2ページ目の選択保持・キャンセルPOST0・選択元だけへ送信', async ({}, info) => {
  test.setTimeout(120_000)
  const page = source.page
  await open(page, '/public/teams/' + b.slug)
  const candidateResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/me/friend-request-source-teams')
  await page.getByTestId('friend-request-entry').click()
  const response = await candidateResponse
  expect(response.status()).toBe(200)
  const body = await response.json()
  expect(body.pagination.totalElements).toBe(21)
  expect(body.pagination.hasNext).toBe(true)
  await expect(page.getByTestId('friend-request-review')).toBeDisabled()
  await page.getByRole('button', { name: '次のページ' }).click()
  await page.getByTestId('friend-request-source').click()
  const last = candidates[20]!
  await page.getByRole('option').filter({ hasText: last.slug }).click()
  await page.getByRole('button', { name: '前のページ' }).click()
  await expect(page.getByTestId('friend-request-source')).toContainText(last.slug)
  const sent: string[] = []
  const track = (request: import('@playwright/test').Request) => { if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/friends/follow')) sent.push(request.url()) }
  page.on('request', track)
  await page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
  expect(sent).toEqual([])
  page.off('request', track)
  await page.getByTestId('friend-request-entry').click()
  await page.getByRole('button', { name: '次のページ' }).click()
  await page.getByTestId('friend-request-source').click()
  await page.getByRole('option').filter({ hasText: last.slug }).click()
  expect(await confirmAndSend(page, last, b, info)).toBe(false)
  await open(page, '/teams/' + last.slug + '/nav')
  await tab(page, 'フォロー中')
  await expect(page.getByTestId('pending-follow-row').filter({ hasText: b.name })).toBeVisible()
  await proof(page, info, 'page2-source-persisted')
})

test('FR-02/04/07: 認可プロフィール1候補、MEMBERは0候補・管理操作なし、他チーム直URL拒否', async ({}, info) => {
  test.setTimeout(120_000)
  await open(target.page, '/teams/' + a.slug + '/info')
  await target.page.getByTestId('friend-request-entry').click()
  await expect(target.page.getByTestId('friend-source-auto')).toBeVisible()
  await expect(target.page.getByTestId('friend-request-source')).toContainText(b.slug)
  await proof(target.page, info, 'authorized-profile-single-source')
  await target.page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
  await open(member.page, '/public/teams/' + b.slug)
  await member.page.getByTestId('friend-request-entry').click()
  await expect(member.page.getByRole('dialog')).toContainText('申請できるチームがありません')
  await expect(member.page.getByTestId('friend-request-review')).toBeDisabled()
  await proof(member.page, info, 'member-zero-source')
  await member.page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
  await open(member.page, '/teams/' + a.slug + '/nav')
  await expect(member.page.getByTestId('friend-guide-button')).toBeVisible()
  await expect(member.page.getByTestId('friend-follow-button')).toHaveCount(0)
  await expect(member.page.getByTestId('incoming-follow-list')).toHaveCount(0)
  await proof(member.page, info, 'member-direct-nav')
  await open(member.page, '/teams/' + b.slug + '/nav')
  await expect(member.page.getByTestId('friend-forbidden')).toBeVisible()
  await expect(member.page.getByTestId('friend-follow-button')).toHaveCount(0)
  await proof(member.page, info, 'other-team-direct-nav-forbidden')
})


test('FR-04/05: 委任MEMBERは操作可能、確認後の権限剥奪は実POST403・再選択を要求し自動送信しない', async ({}, info) => {
  test.setTimeout(120_000)
  await fixture.delegate(source, member, a)
  try {
    await open(member.page, '/public/teams/' + b.slug)
    await member.page.getByTestId('friend-request-entry').click()
    await expect(member.page.getByTestId('friend-source-auto')).toBeVisible()
    await expect(member.page.getByTestId('friend-request-direction')).toContainText(a.slug)
    await member.page.getByTestId('friend-request-review').click()
    await fixture.revoke(source, member, a)
    const response = followResponse(member.page)
    await member.page.getByTestId('friend-follow-submit').click()
    expect((await response).status()).toBe(403)
    await expect(member.page.getByRole('dialog')).toContainText('申請できるチームがありません')
    await expect(member.page.getByTestId('friend-request-review')).toBeDisabled()
    await proof(member.page, info, 'revoked-source-rejected')
  }
  finally { await fixture.revoke(source, member, a) }
})

test('FR-07/10: 申請後に非公開になった元は受信一覧の行を保持し、名前・slug・リンク・フォロー返しを伏せる', async ({}, info) => {
  test.setTimeout(120_000)
  const hidden = candidates[19]!
  await open(source.page, '/teams/' + hidden.slug + '/nav')
  await source.page.getByTestId('friend-follow-button').click()
  await source.page.getByLabel('URL 名で指定する').fill(b.slug)
  expect(await confirmAndSend(source.page, hidden, b, info)).toBe(false)
  // 受信元の状態変更だけを前提APIで行う。申請そのものは上の実UIから発行する。
  const changed = await source.page.request.patch((process.env.API_BASE_URL ?? 'http://localhost:8081') + '/api/v1/teams/' + hidden.slug, { data: { visibility: 'PRIVATE', version: 0 } })
  expect(changed.status()).toBe(200)
  await open(target.page, '/teams/' + b.slug + '/nav')
  await tab(target.page, '届いた申請')
  const row = target.page.getByTestId('incoming-follow-row').filter({ has: target.page.getByTestId('incoming-follow-hidden') })
  await expect(row).toHaveCount(1)
  await expect(row).not.toContainText(hidden.name)
  await expect(row).not.toContainText(hidden.slug)
  await expect(row.getByTestId('incoming-follow-link')).toHaveCount(0)
  await expect(row.getByTestId('incoming-follow-respond')).toHaveCount(0)
  await proof(target.page, info, 'hidden-source-has-no-actions')
})


test('FR-14: 未認証プロフィール→安全なログイン復帰→再選択→キーボード送信、SSR候補0・自動POST0', async ({ browser }, info) => {
  test.setTimeout(180_000)
  await fixture.delegate(source, member, a)
  await member.context.close()
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await context.newPage()
  member = { page, context, userId: member.userId }
  const sourceRequests: string[] = []
  const posts: string[] = []
  page.on('request', r => {
    if (new URL(r.url()).pathname === '/api/v1/me/friend-request-source-teams') sourceRequests.push(r.url())
    if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/friends/follow')) posts.push(r.url())
  })
  try {
    const document = await page.goto('/public/teams/' + b.slug, { waitUntil: 'domcontentloaded' })
    const html = await document!.text()
    expect(html).not.toContain(a.slug)
    await waitForHydration(page)
    await expect(page.getByTestId('friend-request-entry')).toBeVisible()
    expect(sourceRequests).toEqual([])
    expect(posts).toEqual([])
    await page.getByTestId('friend-request-entry').focus()
    await page.keyboard.press('Enter')
    await page.waitForURL(url => url.pathname === '/login')
    const redirect = new URL(page.url()).searchParams.get('redirect')
    expect(redirect).toBe('/public/teams/' + b.slug)
    await page.locator('input#email').fill(process.env.FRIEND_MEMBER_EMAIL!)
    await page.locator('input[type="password"]').fill(process.env.TEST_USER_PASSWORD!)
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()
    await page.waitForURL(url => url.pathname === '/public/teams/' + b.slug)
    await waitForHydration(page)
    expect(posts).toEqual([])
    await expect(page.getByRole('dialog')).toHaveCount(0)
    // ログイン復帰では選択/送信を復元しない。明示的に再度入口を操作する。
    await page.getByTestId('friend-request-entry').focus()
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('friend-request-source')).toContainText(a.slug)
    await expect(page.getByTestId('friend-request-review')).toBeEnabled()
    await proof(page, info, 'login-return-reselected-source-mobile')
    await confirmAndSend(page, a, b, info)
    expect(posts).toHaveLength(1)
  }
  finally { await fixture.revoke(source, member, a) }
})


test('FR-14: 6言語・360/390pxで両プロフィール入口と管理画面の方向確認・横パンなし・操作領域44px', async ({}, info) => {
  test.setTimeout(600_000)
  const names = { ja: '日本語', en: 'English', zh: '中文（简体）', ko: '한국어', es: 'Español', de: 'Deutsch' }
  // 設定を実UIで保存し、同じcontextの新規pageへ引き継ぐ。追加ログインやauthの上書きは行わない。
  for (const locale of ['en', 'zh', 'ko', 'es', 'de', 'ja'] as const) {
    await open(target.page, '/settings/language')
    await target.page.getByRole('combobox').first().click()
    await target.page.getByRole('option', { name: names[locale], exact: true }).click()
    const saved = target.page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname === '/api/v1/users/me')
    await target.page.locator('button[translate="no"]').click()
    expect((await saved).status()).toBe(200)
    const page = await target.context.newPage()
    try {
      for (const width of [360, 390]) {
        await page.setViewportSize({ width, height: 844 })
        for (const path of ['/public/teams/' + a.slug, '/teams/' + a.slug + '/info']) {
          await open(page, path)
          await page.getByTestId('friend-request-entry').click()
          await expect(page.getByTestId('friend-request-source')).toContainText(b.slug)
          await expect(page.getByTestId('friend-request-direction')).toContainText(b.slug)
          await expect(page.getByTestId('friend-request-direction')).toContainText(a.slug)
          await expect(page.getByRole('dialog')).not.toContainText('friends.request.')
          const box = await page.getByTestId('friend-request-review').boundingBox()
          expect(box!.height).toBeGreaterThanOrEqual(44)
          expect(box!.width).toBeGreaterThanOrEqual(44)
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
          await page.getByTestId('friend-request-review').focus()
          await page.keyboard.press('Enter')
          await expect(page.getByTestId('friend-follow-submit')).toBeVisible()
          await proof(page, info, locale + '-' + width + (path.startsWith('/public') ? '-public' : '-authorized'))
          await page.keyboard.press('Escape')
          await expect(page.getByRole('dialog')).toHaveCount(0)
        }
      }
      await open(page, '/teams/' + b.slug + '/nav')
      await page.getByTestId('friend-follow-button').click()
      await page.getByTestId('friend-follow-slug-input').fill(a.slug)
      await expect(page.getByTestId('friend-request-fixed-source')).toContainText(b.slug)
      await expect(page.getByTestId('friend-request-direction')).toContainText(a.slug)
      await proof(page, info, locale + '-nav-fixed-source')
      await page.keyboard.press('Escape')
    }
    finally { await page.close() }
  }
})
