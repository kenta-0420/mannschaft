import { test, expect, type Page, type Browser } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { ActivitySyncFixture, ADMIN, PASSWORD, READER, SCHEDULE_EDITOR,
  OUTSIDER, OTHER_TENANT, resolveScope, type Activity, type Scope } from '../fixtures/activity-sync'

// fixture/ログイン/後始末のみ API を利用。対象操作は実クリック・入力で通す。
async function signIn(page: Page, email = ADMIN): Promise<void> {
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: 'http://localhost:8081', deferNavigation: true })
}
async function detail(page: Page, activity: Pick<Activity, 'id' | 'title'>): Promise<void> {
  await page.goto(`/activities/${activity.id}`)
  await expect(page.getByRole('heading', { name: activity.title, exact: true })).toBeVisible()
}
async function schedule(page: Page, scope: Scope, id: number): Promise<void> {
  await page.goto(`${scope.path}/schedule?eventId=${id}`)
  await expect(page.getByTestId('schedule-edit')).toBeVisible()
}
async function editScheduleTitle(page: Page, title: string): Promise<void> {
  await page.getByTestId('schedule-edit').click()
  await page.getByTestId('schedule-title').fill(title)
  await page.getByTestId('schedule-submit').click()
}
async function mutation(page: Page, path: string, action: () => Promise<void>): Promise<void> {
  const [response] = await Promise.all([
    page.waitForResponse((res) => new URL(res.url()).pathname.endsWith(path) && ['PATCH', 'PUT', 'POST'].includes(res.request().method())),
    action(),
  ])
  expect(response.ok(), `UI 送信 ${response.status()} ${await response.text()}`).toBeTruthy()
}
async function rolePage(browser: Browser, email?: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:3001', locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  if (email) await signIn(page, email)
  return page
}

for (const type of ['TEAM', 'ORGANIZATION'] as const) {
  test(`${type} 予定詳細から跨日の下書きを作り相互リンクと再利用を確認する`, async ({ page }) => {
    await signIn(page)
    const scope = await resolveScope(type)
    const fixture = new ActivitySyncFixture(page, scope)
    const title = `実機跨日-${type}-${Date.now()}`
    try {
      const id = await fixture.schedule(title)
      await schedule(page, scope, id)
      const [created] = await Promise.all([
        page.waitForResponse((res) => res.url().includes('/activities/draft-from-schedule') && res.request().method() === 'POST'),
        page.getByTestId('schedule-create-activity').click(),
      ])
      expect(created.status()).toBe(200)
      const activity = (await created.json() as { data: Activity }).data
      fixture.activityIds.push(activity.id)
      await expect(page).toHaveURL(new RegExp(`/activities/${activity.id}$`))
      await expect(page.getByTestId('activity-datetime')).toContainText('2026-10-15')
      await expect(page.getByTestId('activity-datetime')).toContainText('2026-10-16')
      await expect(page.getByTestId('activity-datetime')).toContainText('23:00')
      await expect(page.getByTestId('activity-datetime')).toContainText('01:00')
      await expect(page.getByTestId('activity-description')).toHaveCount(0)
      await page.getByTestId('activity-source-schedule').click()
      await expect(page).toHaveURL(new RegExp(`eventId=${id}`))
      await page.getByTestId(`schedule-activity-${activity.id}`).click()
      await expect(page).toHaveURL(new RegExp(`/activities/${activity.id}$`))
      await page.goto(`${scope.path}/activities`)
      await page.getByTestId(`activity-detail-${activity.id}`).click()
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      const persisted = await fixture.detail(activity.id)
      expect(persisted.activityEndDate).toBe('2026-10-16')
      expect(persisted.participants).toEqual([])
    } finally { await fixture.cleanup() }
  })
}

test('未編集 DRAFT の予定由来タイトルだけ自動追従し活動本文を保持する', async ({ page }) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  try {
    const id = await fixture.schedule(`実機自動-${Date.now()}`)
    const draft = await fixture.linkedDraft(id)
    await detail(page, draft)
    await page.getByTestId('activity-edit-draft').click()
    await page.getByTestId('activity-edit-description').fill('**実績本文を保持**')
    await mutation(page, `/activities/${draft.id}`, () => page.getByTestId('activity-edit-save').click())
    await expect(page.getByTestId('activity-description')).toContainText('実績本文を保持')
    const before = await fixture.detail(draft.id)
    const title = `実機自動更新-${Date.now()}`
    await schedule(page, scope, id)
    await mutation(page, `/schedules/${id}`, () => editScheduleTitle(page, title))
    await detail(page, { id: draft.id, title })
    await expect(page.getByTestId('activity-description')).toContainText('実績本文を保持')
    const after = await fixture.detail(draft.id)
    expect(after.description).toBe(before.description)
    expect(after.fieldValues).toBe(before.fieldValues)
    expect(after.attachments).toBe(before.attachments)
    expect(after.participants).toEqual(before.participants)
  } finally { await fixture.cleanup() }
})

test('手動編集差分を取消・予定のみ保存・選択適用できる', async ({ page }) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  try {
    const id = await fixture.schedule(`実機差分-${Date.now()}`)
    const draft = await fixture.linkedDraft(id)
    const manual = `実機手入力-${Date.now()}`
    await detail(page, draft)
    await page.getByTestId('activity-edit-draft').click()
    await page.getByTestId('activity-edit-title').fill(manual)
    await mutation(page, `/activities/${draft.id}`, () => page.getByTestId('activity-edit-save').click())
    await schedule(page, scope, id)
    const scheduled = `実機予定差分-${Date.now()}`
    await editScheduleTitle(page, scheduled)
    await expect(page.getByTestId('activity-sync-apply')).toBeVisible()
    await page.getByTestId('activity-sync-cancel').click()
    expect((await fixture.detail(draft.id)).title).toBe(manual)
    await page.getByTestId('schedule-submit').click()
    await mutation(page, `/schedules/${id}`, () => page.getByTestId('activity-sync-schedule-only').click())
    await detail(page, { id: draft.id, title: manual })
    await schedule(page, scope, id)
    const selected = `${scheduled}-選択`
    await editScheduleTitle(page, selected)
    await expect(page.getByTestId('activity-sync-apply')).toBeVisible()
    await page.locator(`[id="sync-${draft.id}-title"]`).check()
    await mutation(page, `/schedules/${id}`, () => page.getByTestId('activity-sync-apply').click())
    await detail(page, { id: draft.id, title: selected })
  } finally { await fixture.cleanup() }
})

test('閲覧専用と他tenant・匿名は画面と直 URL で認可される', async ({ page, browser }) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  const contexts: Page[] = []
  try {
    const published = await fixture.published(`実機認可-${Date.now()}`)
    const id = await fixture.schedule(`実機非公開-${Date.now()}`)
    const hidden = await fixture.linkedDraft(id)
    const reader = await rolePage(browser, READER)
    contexts.push(reader)
    await reader.goto(`${scope.path}/activities`)
    await reader.getByTestId(`activity-detail-${published.id}`).click()
    await expect(reader.getByTestId('activity-description')).toContainText('保持する活動本文')
    await expect(reader.getByTestId('activity-template-fields')).toContainText('0')
    await expect(reader.getByTestId('activity-template-fields')).toContainText('過去値')
    await expect(reader.getByTestId('activity-edit-draft')).toHaveCount(0)
    await expect(reader.getByTestId('activity-publish')).toHaveCount(0)
    await reader.goto(`/activities/${hidden.id}`)
    await expect(reader.getByTestId('load-error-state')).toBeVisible()
    await expect(reader.getByRole('heading', { name: hidden.title, exact: true })).toHaveCount(0)
    for (const email of [OUTSIDER, OTHER_TENANT, undefined]) {
      const denied = await rolePage(browser, email)
      contexts.push(denied)
      await denied.goto(`/activities/${published.id}`)
      await expect(denied.getByRole('heading', { name: published.title, exact: true })).toHaveCount(0)
      if (email) await expect(denied.getByTestId('load-error-state')).toBeVisible()
      else await expect(denied).toHaveURL(/\/login/)
    }
  } finally {
    for (const member of contexts) await member.context().close()
    await fixture.cleanup()
  }
})

test('予定のみ編集権の MEMBER が予定を更新しても他作者の記録を上書きしない', async ({ page, browser }) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  let editor: Page | undefined
  try {
    await fixture.scheduleOnlyEditor()
    const id = await fixture.schedule(`実機分離-${Date.now()}`)
    const activity = await fixture.published(`実機保護-${Date.now()}`, id)
    const before = await fixture.detail(activity.id)
    editor = await rolePage(browser, SCHEDULE_EDITOR)
    await schedule(editor, scope, id)
    await expect(editor.getByTestId(`schedule-activity-${activity.id}`)).toBeVisible()
    await mutation(editor, `/schedules/${id}`, () => editScheduleTitle(editor!, `実機予定担当-${Date.now()}`))
    await detail(editor, activity)
    await expect(editor.getByTestId('activity-edit-draft')).toHaveCount(0)
    const after = await fixture.detail(activity.id)
    expect(after.title).toBe(before.title)
    expect(after.version).toBe(before.version)
    expect(after.description).toBe(before.description)
  } finally {
    await editor?.context().close()
    await fixture.cleanup()
  }
})
