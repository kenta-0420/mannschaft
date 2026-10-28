/**
 * CMP-260827-1152 / #2997 G5: アンケート督促・締切延長 の付随通知（業務TXから分離済み）実機E2E。
 *
 * SURVEY-G5-1  督促: 管理者が画面で「督促送信」→ 応答文言は「送信を受け付けました」(M6)
 *              → 未回答メンバーの通知一覧画面に「アンケート未回答のお知らせ」が出る
 * SURVEY-G5-2  権限なし(MEMBER)は督促導線が出ない / 他テナントは詳細URL直打ちでも内容が出ない
 * SURVEY-G5-3  締切延長: 画面導線が存在しない（欠陥）ことを実証し、API 補助で延長して通知到達のみ画面確認
 */
import { expect, test, request as pwRequest, type APIRequestContext, type BrowserContext } from '@playwright/test'
import {
  BE_API, MEMBER, OUTSIDER, OWNER, apiLogin, authHeaders, createThrowawayTeam, dbScalar, deleteNotificationsByIds, deleteTeam,
  expectNotificationOnScreen, expectNoNotificationOnScreen, gotoHydrated, joinAsMember, maxNotificationId, openSession,
} from './helpers/g3457-notify'

test.describe.configure({ mode: 'serial' })

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let team: { slug: string; id: number; name: string }
let surveyId = 0
const surveyTitle = `G3457Remind_${Date.now()}`
const sessions: BrowserContext[] = []

let baseline = 0

test.beforeAll(async () => {
  baseline = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'survey')
  const role = await joinAsMember(ownerCtx, ownerToken, memberCtx, memberToken, team.slug)
  expect(role).toBe('MEMBER')

  const create = await ownerCtx.post(`${BE_API}/teams/${team.slug}/surveys`, {
    headers: authHeaders(ownerToken),
    data: {
      title: surveyTitle, description: 'G3457 実機E2E（後始末で削除）', isAnonymous: false,
      allowMultipleSubmissions: false, resultsVisibility: 'ADMINS_ONLY', distributionMode: 'ALL',
      unrespondedVisibility: 'ALL_MEMBERS',
      expiresAt: new Date(Date.now() + 2 * 86400_000).toISOString().slice(0, 19),
      questions: [{ questionType: 'FREE_TEXT', questionText: 'ひとこと', isRequired: false, displayOrder: 1 }],
    },
  })
  expect(create.status(), await create.text()).toBe(201)
  surveyId = (await create.json()).data.id
  const pub = await ownerCtx.post(`${BE_API}/teams/${team.slug}/surveys/${surveyId}/publish`, { headers: authHeaders(ownerToken) })
  expect(pub.ok(), await pub.text()).toBeTruthy()
})

test.afterAll(async () => {
  for (const c of sessions) await c.close().catch(() => {})
  try {
    // このテストが作ったアンケート(固有タイトル)に紐づく通知だけを ID 指定で削除
    const ids = dbScalar(`select id from notifications where id > ${baseline} and (instr(title, '${surveyTitle}') > 0 or instr(body, '${surveyTitle}') > 0)`)
    deleteNotificationsByIds(ids ? ids.split(/\s+/).map(Number) : [])
  } catch (e) { console.log('[後始末] 通知削除失敗', String(e)) }
  if (surveyId) {
    const r = await ownerCtx.delete(`${BE_API}/teams/${team.slug}/surveys/${surveyId}`, { headers: authHeaders(ownerToken) })
    console.log(`[後始末] survey ${surveyId} 削除 status=${r.status()}`)
  }
  if (team) await deleteTeam(ownerCtx, ownerToken, team.slug)
  await ownerCtx.dispose(); await memberCtx.dispose()
})

const detailUrl = () => `/surveys/${surveyId}?scope=team&scopeId=${team.id}`

test('SURVEY-G5-1: 管理者が督促を画面で送信し、未回答メンバーの通知一覧に届く（応答文言はM6）', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await gotoHydrated(page, detailUrl())
  await expect(page.getByTestId('survey-detail-page')).toBeVisible({ timeout: 60_000 })
  await page.getByTestId('survey-respondents-toggle').click()
  await expect(page.getByTestId('survey-respondents-list')).toBeVisible({ timeout: 30_000 })
  await page.getByTestId('respondents-filter').getByText(/未回答/).click()
  const remindBtn = page.getByTestId('respondents-remind-button')
  await expect(remindBtn).toBeVisible()
  const resp = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/v1\/surveys\/\d+\/remind$/.test(r.url()))
  await remindBtn.click()
  const r = await resp
  expect(r.status(), await r.text()).toBe(200)
  await expect(page.getByText(/名の未回答メンバーにリマインドの送信を受け付けました/)).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText(/送信しました/)).toHaveCount(0)
  await page.screenshot({ path: 'test-results/g3457-survey-1-owner-remind.png', fullPage: true })

  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, 'アンケート未回答のお知らせ', { shot: 'g3457-survey-1-member-notification' })
  await expect(mem.page.getByText(new RegExp(surveyTitle)).first()).toBeVisible()
})

test('SURVEY-G5-2: 権限なしには督促導線が出ず、他テナントは詳細URL直打ちでも弾かれる', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await gotoHydrated(mem.page, detailUrl())
  await expect(mem.page.getByTestId('survey-detail-page')).toBeVisible({ timeout: 60_000 })
  await expect(mem.page.getByTestId('survey-respondents-toggle')).toHaveCount(0)
  await expect(mem.page.getByTestId('respondents-remind-button')).toHaveCount(0)
  await mem.page.screenshot({ path: 'test-results/g3457-survey-2-member-no-remind.png', fullPage: true })
  // 権限なしが API を直接叩いても拒否（FE の出し分けだけに依存しない）
  const direct = await memberCtx.post(`${BE_API}/surveys/${surveyId}/remind`, { headers: authHeaders(memberToken) })
  expect([400, 403, 404]).toContain(direct.status())

  const out = await openSession(browser, OUTSIDER); sessions.push(out.context)
  await gotoHydrated(out.page, detailUrl())
  await out.page.waitForTimeout(4_000)
  await expect(out.page.getByText('アンケート情報を取得できませんでした')).toBeVisible()
  await expect(out.page.getByText(surveyTitle)).toHaveCount(0)
  await expect(out.page.getByTestId('survey-respondents-toggle')).toHaveCount(0)
  await out.page.screenshot({ path: 'test-results/g3457-survey-2-outsider-direct-url.png', fullPage: true })
  // 他テナントの通知一覧には、このアンケート(固有タイトル)に関する通知が一件も出ない
  await expectNoNotificationOnScreen(out.page, new RegExp(surveyTitle), 2_000)
})

test('SURVEY-G5-3: 締切延長は画面導線なし（欠陥）。API補助で延長して通知到達のみ画面確認', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  await gotoHydrated(owner.page, detailUrl())
  await expect(owner.page.getByTestId('survey-detail-page')).toBeVisible({ timeout: 60_000 })
  // 締切延長のボタン・メニューが画面に存在しない
  await expect(owner.page.getByRole('button', { name: /延長/ })).toHaveCount(0)
  await expect(owner.page.getByText(/締切を延長|期限を延長/)).toHaveCount(0)
  await owner.page.screenshot({ path: 'test-results/g3457-survey-3-no-extend-ui.png', fullPage: true })

  // 【API補助（導線欠落のため代替）】対象操作そのものを画面で行えないので、延長だけ API で実行する
  const newDeadline = new Date(Date.now() + 5 * 86400_000).toISOString().slice(0, 19)
  const ext = await ownerCtx.post(`${BE_API}/teams/${team.id}/surveys/${surveyId}/extend`, {
    headers: authHeaders(ownerToken), data: { new_deadline: newDeadline },
  })
  console.log(`[SURVEY-G5-3] extend(API補助) status=${ext.status()} ${(await ext.text()).slice(0, 200)}`)
  expect(ext.ok()).toBeTruthy()

  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, 'アンケート締切が延長されました', { shot: 'g3457-survey-3-member-notification' })
})
