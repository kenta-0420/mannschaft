/**
 * CMP-261021-0647: 実マイシフトからの交代依頼、承諾、管理者承認。
 * モックなし。API補助はログイン・前提作成・後始末に限定する。
 * 秘密の役者情報は CMP0647_FIXTURE_PATH のローカルJSONから読み、commitしない。
 * 1/100/101件と通信故障の網羅はcomponent試験が担当し、ここでは実APIの経路を守る。
 */
import fs from 'node:fs'
import { test, expect, type APIResponse, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

interface Credentials { email: string; password: string; userId: number }
interface FixtureConfig {
  team: { slug: string; numericId: number }
  admin: Credentials
  requester: Credentials
  target: Credentials
  supporter: Credentials
  outsider: Credentials
}
interface Actor { page: Page; context: BrowserContext; credentials: Credentials }
interface Swap { id: number; slotId: number; requesterId: number; accepterId: number | null; status: string; recipientMode: string; targetUserIds: number[] }

const FE = process.env.BASE_URL ?? 'http://localhost:3007'
const API = process.env.API_BASE_URL ?? 'http://localhost:8087'
const PREFIX = `${API}/api/v1`
test.use({ storageState: { cookies: [], origins: [] } })

async function data<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `前提API HTTP ${response.status()}`).toBe(true)
  return ((await response.json()) as { data: T }).data
}

async function openActor(browser: Browser, credentials: Credentials): Promise<Actor> {
  const context = await browser.newContext({ baseURL: FE, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API })
  const me = await page.evaluate(() => JSON.parse(localStorage.getItem('currentUser') ?? '{}') as { id: number })
  expect(me.id, 'fixtureの利用者と実認証主体が一致').toBe(credentials.userId)
  return { page, context, credentials }
}

function swapResponse(page: Page, method: string, suffix = '') {
  return page.waitForResponse(r => r.request().method() === method
    && new URL(r.url()).pathname === `/api/v1/shifts/swap-requests${suffix}`)
}

async function exchangeTab(actor: Actor, slug: string): Promise<void> {
  await actor.page.goto(`/teams/${slug}/shifts`)
  await waitForHydration(actor.page)
  await actor.page.getByRole('tab', { name: 'シフト交換', exact: true }).click()
}

test('0647 実UIで3モードを提出し、別MEMBER承諾とADMIN承認を永続化・認可表示まで確認する', async ({ browser }, info) => {
  test.setTimeout(600_000)
  const configPath = process.env.CMP0647_FIXTURE_PATH
  expect(configPath, '専用環境の秘密fixture JSONが必要').toBeTruthy()
  const config = JSON.parse(fs.readFileSync(configPath!, 'utf8')) as FixtureConfig
  const actors: Actor[] = []
  const schedules: number[] = []
  const positions: number[] = []
  const pending: number[] = []
  let admin: Actor | undefined
  let requester: Actor | undefined
  const evidence: object[] = []

  try {
    admin = await openActor(browser, config.admin); actors.push(admin)
    requester = await openActor(browser, config.requester); actors.push(requester)
    const target = await openActor(browser, config.target); actors.push(target)
    const supporter = await openActor(browser, config.supporter); actors.push(supporter)
    const outsider = await openActor(browser, config.outsider); actors.push(outsider)

    for (const [actor, role] of [[admin, 'ADMIN'], [requester, 'MEMBER'], [target, 'MEMBER'], [supporter, 'SUPPORTER']] as const) {
      const permissions = await data<{ roleName: string }>(await actor.page.request.get(`${PREFIX}/teams/${config.team.slug}/me/permissions`))
      expect(permissions.roleName, 'SYSTEM_ADMIN等の上位権限で偽の成功にしない').toBe(role)
    }

    const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Tokyo' }).format(new Date())
    const title = `CMP0647実機_${Date.now()}`
    const position = await data<{ id: number }>(await admin.page.request.post(`${PREFIX}/shifts/positions?teamId=${config.team.numericId}`, { data: { name: title, displayOrder: 1 } }))
    positions.push(position.id)
    const schedule = await data<{ id: number }>(await admin.page.request.post(`${PREFIX}/shifts/schedules?teamId=${config.team.numericId}`, { data: { title, startDate: day, endDate: day } }))
    schedules.push(schedule.id)
    const privateTitle = `${title}_未公開`
    const draft = await data<{ id: number }>(await admin.page.request.post(`${PREFIX}/shifts/schedules?teamId=${config.team.numericId}`, { data: { title: privateTitle, startDate: day, endDate: day } }))
    schedules.push(draft.id)
    const slots: number[] = []
    for (const [start, end, userId] of [
      ['09:00:00', '10:00:00', config.requester.userId],
      ['10:00:00', '11:00:00', config.requester.userId],
      ['11:00:00', '12:00:00', config.requester.userId],
      ['13:00:00', '16:00:00', config.target.userId],
    ] as const) {
      const slot = await data<{ id: number }>(await admin.page.request.post(`${PREFIX}/shifts/schedules/${schedule.id}/slots`, { data: { slotDate: day, startTime: start, endTime: end, positionId: position.id, requiredCount: 1 } }))
      slots.push(slot.id)
      expect((await admin.page.request.patch(`${PREFIX}/shifts/slots/${slot.id}/assignments`, { data: { addUserIds: [userId], removeUserIds: [], slotVersion: 0 } })).ok()).toBe(true)
    }
    for (const status of ['COLLECTING', 'ADJUSTING', 'PUBLISHED']) {
      expect((await admin.page.request.post(`${PREFIX}/shifts/schedules/${schedule.id}/transition?status=${status}`)).ok()).toBe(true)
    }

    await requester.page.setViewportSize({ width: 390, height: 844 })
    for (const [index, mode] of ['SPECIFIC', 'TEMPLATE', 'OPEN_CALL'].entries()) {
      const candidateRequests: string[] = []
      const record = (r: import('@playwright/test').Request) => {
        if (/\/teams\/[^/]+\/members(?:\/all)?$/.test(new URL(r.url()).pathname)) candidateRequests.push(r.url())
      }
      requester.page.on('request', record)
      await requester.page.goto('/my/shift')
      await waitForHydration(requester.page)
      await requester.page.getByRole('button', { name: '今日', exact: true }).click()
      const buttons = requester.page.getByRole('button', { name: '交代依頼を作成', exact: true })
      await expect(buttons).toHaveCount(3)
      await buttons.nth(index).click()
      const dialog = requester.page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await expect(dialog.getByRole('button', { name: '提出', exact: true })).toBeDisabled()
      const checkbox = dialog.locator(`#member-${config.target.userId}`)
      await expect(checkbox).toBeVisible()
      await expect.poll(() => candidateRequests.filter(u => new URL(u).pathname.endsWith('/members/all')).length).toBe(1)
      expect(candidateRequests).toHaveLength(1)
      expect(new URL(candidateRequests[0]!).pathname).toBe(`/api/v1/teams/${config.team.slug}/members/all`)
      if (mode === 'SPECIFIC') {
        await checkbox.check()
        await expect(dialog.getByText('1名を選択中', { exact: true })).toBeVisible()
      } else if (mode === 'TEMPLATE') {
        await dialog.getByRole('button', { name: 'シフト帯でまとめて', exact: true }).click()
        await dialog.getByRole('button', { name: /夜シフト担当者/ }).click()
      } else {
        await dialog.getByRole('button', { name: '全員に公開', exact: true }).click()
      }
      await dialog.getByPlaceholder('理由', { exact: true }).fill(`${title}_${mode}`)
      const createdPromise = swapResponse(requester.page, 'POST')
      await dialog.getByRole('button', { name: '提出', exact: true }).click()
      const createdResponse = await createdPromise
      expect(createdResponse.status()).toBe(201)
      const created = ((await createdResponse.json()) as { data: Swap }).data
      pending.push(created.id)
      expect(created.slotId).toBe(slots[index])
      expect(created.requesterId).toBe(config.requester.userId)
      expect(created.status).toBe('PENDING')
      expect(created.recipientMode).toBe(mode === 'OPEN_CALL' ? 'OPEN_CALL' : 'SPECIFIC')
      if (mode !== 'OPEN_CALL') expect(created.targetUserIds).toEqual([config.target.userId])
      await expect(dialog).toBeHidden()
      requester.page.off('request', record)
      expect(await requester.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

      await exchangeTab(target, config.team.slug)
      const row = target.page.getByTestId(`swap-row-${created.id}`)
      await expect(row).toContainText('承諾待ち')
      const acceptedPromise = swapResponse(target.page, 'POST', `/${created.id}/accept`)
      await row.getByTestId(`swap-accept-${created.id}`).click()
      const accepted = ((await (await acceptedPromise).json()) as { data: Swap }).data
      expect(accepted.status).toBe('ACCEPTED')
      expect(accepted.accepterId).toBe(config.target.userId)
      await expect(row).toContainText('承認待ち')
      await expect(row.getByTestId(`swap-approve-${created.id}`)).toHaveCount(0)

      await exchangeTab(admin, config.team.slug)
      const adminRow = admin.page.getByTestId(`swap-row-${created.id}`)
      await expect(adminRow).toContainText('承認待ち')
      const approvedPromise = swapResponse(admin.page, 'POST', `/${created.id}/resolve`)
      await adminRow.getByTestId(`swap-approve-${created.id}`).click()
      expect(((await (await approvedPromise).json()) as { data: Swap }).data.status).toBe('APPROVED')
      pending.splice(pending.indexOf(created.id), 1)
      await expect(adminRow).toContainText('承認済み')
      // GETも実画面の再取得で観測し、対象操作をAPIヘルパーで代替しない。
      const persistedPromise = swapResponse(admin.page, 'GET')
      await exchangeTab(admin, config.team.slug)
      const persisted = ((await (await persistedPromise).json()) as { data: Swap[] }).data.find(r => r.id === created.id)
      expect(persisted?.status).toBe('APPROVED')
      await expect(admin.page.getByTestId(`swap-row-${created.id}`)).toContainText('承認済み')
      await admin.page.screenshot({ path: info.outputPath(`${mode}-approved.png`), fullPage: true })
      evidence.push({ mode, start: '/my/shift', entry: '今日→交代依頼を作成', slotId: created.slotId, requestId: created.id, candidateRequests, statuses: ['PENDING', 'ACCEPTED', 'APPROVED'], persisted, apiAuxiliary: ['ログイン', '前提', '後始末'] })
    }

    // 同チームMEMBERにも未公開データを秘匿する。公開済みが見える正の比較を先に取る。
    await requester.page.goto(`/teams/${config.team.slug}/shifts`)
    await expect(requester.page.getByText(title, { exact: true })).toBeVisible()
    await expect(requester.page.getByText(privateTitle, { exact: true })).toHaveCount(0)
    for (const actor of [requester, outsider]) {
      const detail = actor.page.waitForResponse(r => r.request().method() === 'GET'
        && new URL(r.url()).pathname === `/api/v1/shifts/schedules/${draft.id}`)
      const slotsResponse = actor.page.waitForResponse(r => r.request().method() === 'GET'
        && new URL(r.url()).pathname === `/api/v1/shifts/schedules/${draft.id}/slots`)
      await actor.page.goto(`/shift/${draft.id}`)
      expect((await detail).status()).toBe(404)
      expect((await slotsResponse).status()).toBe(404)
      await expect(actor.page.locator('.p-toast-message').first()).toBeVisible()
      await expect(actor.page.getByText(privateTitle, { exact: true })).toHaveCount(0)
      await actor.page.screenshot({ path: info.outputPath(`private-${actor === requester ? 'member' : 'outsider'}.png`), fullPage: true })
      evidence.push({ actor: actor === requester ? 'MEMBER' : '他テナント', directUrl: actor.page.url(), deniedStatus: 404, hiddenTitle: privateTitle, screenText: (await actor.page.locator('body').innerText()).slice(0, 2000) })
    }

    for (const actor of [supporter, outsider]) {
      let deniedStatus: number
      if (actor === supporter) {
        const denied = swapResponse(actor.page, 'GET')
        await exchangeTab(actor, config.team.slug)
        deniedStatus = (await denied).status()
        expect(deniedStatus).toBe(403)
        await expect(actor.page.getByText('交換リクエストを取得できませんでした', { exact: true })).toBeVisible()
      } else {
        const denied = actor.page.waitForResponse(r => r.request().method() === 'GET'
          && new URL(r.url()).pathname === `/api/v1/teams/${config.team.slug}`)
        await actor.page.goto(`/teams/${config.team.slug}/shifts`)
        deniedStatus = (await denied).status()
        expect([403, 404]).toContain(deniedStatus)
        await expect(actor.page.locator('body')).toContainText(/エラー|見つかりません|アクセス/)
      }
      await expect(actor.page.getByTestId(/^swap-row-/)).toHaveCount(0)
      await expect(actor.page.getByRole('button', { name: '承認', exact: true })).toHaveCount(0)
      await actor.page.screenshot({ path: info.outputPath(`blocked-${actor === supporter ? 'supporter' : 'outsider'}.png`), fullPage: true })
      evidence.push({ actor: actor === supporter ? 'SUPPORTER' : '他テナント', deniedStatus, directUrl: actor.page.url(), screenText: (await actor.page.locator('body').innerText()).slice(0, 2000) })
    }
  } finally {
    const failures: string[] = []
    if (requester) for (const id of pending) {
      const response = await requester.page.request.delete(`${PREFIX}/shifts/swap-requests/${id}`)
      if (![204, 404].includes(response.status())) failures.push(`swap ${id}: ${response.status()}`)
    }
    if (admin) {
      for (const id of schedules) {
        const response = await admin.page.request.delete(`${PREFIX}/shifts/schedules/${id}`)
        if (![204, 404].includes(response.status())) failures.push(`schedule ${id}: ${response.status()}`)
      }
      for (const id of positions) {
        const response = await admin.page.request.delete(`${PREFIX}/shifts/positions/${id}`)
        if (![204, 404].includes(response.status())) failures.push(`position ${id}: ${response.status()}`)
      }
    }
    await info.attach('UI経路・実BE結果・後始末', { body: JSON.stringify({ evidence, cleanup: { schedules, positions, failures } }, null, 2), contentType: 'application/json' })
    for (const actor of actors) await actor.context.close()
    expect(failures, '所有fixtureの後始末が成功').toEqual([])
  }
})
