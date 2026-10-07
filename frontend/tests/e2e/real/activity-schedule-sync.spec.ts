import { test, expect, type Page, type Browser } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { loginViaApi } from '../fixtures/auth'
import {
  ActivitySyncFixture,
  ADMIN,
  TEAM_ADMIN,
  API,
  data,
  PASSWORD,
  READER,
  SCHEDULE_EDITOR,
  OUTSIDER,
  OTHER_TENANT,
  resolveScope,
  success,
  type Activity,
  type Scope,
} from '../fixtures/activity-sync'

// fixture/ログイン/後始末のみ API を利用。対象操作は実クリック・入力で通す。
async function signIn(page: Page, email = ADMIN): Promise<void> {
  await loginViaApi(
    page,
    { email, password: PASSWORD },
    { apiBaseUrl: 'http://localhost:8081', deferNavigation: true },
  )
}
async function detail(page: Page, activity: Pick<Activity, 'id' | 'title'>): Promise<void> {
  await page.goto(`/activities/${activity.id}`)
  await expect(page.getByRole('heading', { name: activity.title, exact: true })).toBeVisible()
}
async function schedule(page: Page, scope: Scope, id: number): Promise<void> {
  await page.goto(`${scope.path}/schedule?eventId=${id}`)
  await expect(page.locator('[data-testid="schedule-edit"]:visible')).toBeVisible()
  const deferPermissions = page.getByRole('button', { name: 'あとで決める', exact: true })
  if (await deferPermissions.isVisible()) await deferPermissions.click()
}
async function editScheduleTitle(page: Page, title: string): Promise<void> {
  await page.locator('[data-testid="schedule-edit"]:visible').click()
  await page.getByTestId('schedule-title').fill(title)
  await page.getByTestId('schedule-submit').click()
}
async function mutation(page: Page, path: string, action: () => Promise<void>): Promise<void> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (res) =>
        new URL(res.url()).pathname.endsWith(path) &&
        ['PATCH', 'PUT', 'POST'].includes(res.request().method()),
    ),
    action(),
  ])
  expect(response.ok(), `UI 送信 ${response.status()} ${await response.text()}`).toBeTruthy()
}
async function rolePage(browser: Browser, email?: string): Promise<Page> {
  const context = await browser.newContext({
    baseURL: 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  })
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
        page.waitForResponse(
          (res) =>
            res.url().includes('/activities/draft-from-schedule') &&
            res.request().method() === 'POST',
        ),
        page.locator('[data-testid="schedule-create-activity"]:visible').click(),
      ])
      expect(created.status()).toBe(200)
      const activity = ((await created.json()) as { data: Activity }).data
      expect(activity.scopePublicId).toBe(scope.slug)
      fixture.activityIds.push(activity.id)
      await expect(page).toHaveURL(new RegExp(`/activities/${activity.id}$`))
      await expect(page.getByTestId('activity-datetime')).toContainText('2026-10-15')
      await expect(page.getByTestId('activity-datetime')).toContainText('2026-10-16')
      await expect(page.getByTestId('activity-datetime')).toContainText('23:00')
      await expect(page.getByTestId('activity-datetime')).toContainText('01:00')
      await expect(page.getByTestId('activity-description')).toHaveCount(0)
      await page.getByTestId('activity-source-schedule').click()
      await expect(page).toHaveURL(new RegExp(`eventId=${id}`))
      await expect(
        page.locator('.lg\\:col-span-2:visible').getByText(title, { exact: true }),
      ).toHaveCount(1)
      await page.locator(`[data-testid="schedule-activity-${activity.id}"]:visible`).click()
      await expect(page).toHaveURL(new RegExp(`/activities/${activity.id}$`))
      await page.getByRole('button', { name: '活動記録', exact: true }).click()
      await expect(page).toHaveURL(`${scope.path}/activities`)
      await page.getByTestId(`activity-detail-${activity.id}`).click()
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      const persisted = await fixture.detail(activity.id)
      expect(persisted.activityEndDate).toBe('2026-10-16')
      expect(persisted.participants).toEqual([])
    } finally {
      await fixture.cleanup()
    }
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
    await mutation(page, `/activities/${draft.id}`, () =>
      page.getByTestId('activity-edit-save').click(),
    )
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
  } finally {
    await fixture.cleanup()
  }
})

test('手動編集差分を取消・予定のみ保存・選択適用できる', async ({ page }) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  try {
    const original = `実機差分-${Date.now()}`
    const id = await fixture.schedule(original)
    const draft = await fixture.linkedDraft(id)
    const edited = `実機手入力-${Date.now()}`
    const manual = draft.title
    await detail(page, draft)
    await page.getByTestId('activity-edit-draft').click()
    await page.getByTestId('activity-edit-title').fill(edited)
    await mutation(page, `/activities/${draft.id}`, () =>
      page.getByTestId('activity-edit-save').click(),
    )
    await detail(page, { id: draft.id, title: edited })
    await page.getByTestId('activity-edit-draft').click()
    await page.getByTestId('activity-edit-title').fill(manual)
    await mutation(page, `/activities/${draft.id}`, () =>
      page.getByTestId('activity-edit-save').click(),
    )
    // 一度手動変更した値を元値に戻しても手動フラグは消えず差分確認を要する。
    await schedule(page, scope, id)
    const scheduled = `実機予定差分-${Date.now()}`
    await editScheduleTitle(page, scheduled)
    await expect(page.getByTestId('activity-sync-apply')).toBeVisible()
    await page.getByTestId('activity-sync-cancel').click()
    expect((await fixture.detail(draft.id)).title).toBe(manual)
    expect(
      (await data<{ title: string }>(await page.request.get(`${fixture.schedules}/${id}`))).title,
    ).toBe(original)
    await page.getByTestId('schedule-submit').click()
    await mutation(page, `/schedules/${id}`, () =>
      page.getByTestId('activity-sync-schedule-only').click(),
    )
    await detail(page, { id: draft.id, title: manual })
    await schedule(page, scope, id)
    const selected = `${scheduled}-選択`
    await editScheduleTitle(page, selected)
    await expect(page.getByTestId('activity-sync-apply')).toBeVisible()
    await page.locator(`[id="sync-${draft.id}-title"]`).check()
    await mutation(page, `/schedules/${id}`, () => page.getByTestId('activity-sync-apply').click())
    await detail(page, { id: draft.id, title: selected })
  } finally {
    await fixture.cleanup()
  }
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
    await expect(reader.getByTestId('activity-template-fields')).toContainText('false')
    await expect(reader.getByTestId('activity-template-fields')).toContainText('—')
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
      await expect(denied.getByRole('heading', { name: published.title, exact: true })).toHaveCount(
        0,
      )
      if (email) await expect(denied.getByTestId('load-error-state')).toBeVisible()
      else await expect(denied).toHaveURL(/\/login/)
    }
  } finally {
    for (const member of contexts) await member.context().close()
    await fixture.cleanup()
  }
})

for (const options of [
  { allDay: true, endAt: null },
  { allDay: false, endAt: null },
]) {
  test(`${options.allDay ? '終日' : '終了なし'}予定から画面で下書き作成し null 時刻を保持する`, async ({
    page,
  }) => {
    await signIn(page)
    const scope = await resolveScope('TEAM')
    const fixture = new ActivitySyncFixture(page, scope)
    try {
      const title = `実機空終了-${options.allDay}-${Date.now()}`
      const id = await fixture.schedule(title, options)
      await schedule(page, scope, id)
      const [response] = await Promise.all([
        page.waitForResponse(
          (res) =>
            res.url().includes('/activities/draft-from-schedule') &&
            res.request().method() === 'POST',
        ),
        page.locator('[data-testid="schedule-create-activity"]:visible').click(),
      ])
      expect(response.ok()).toBeTruthy()
      const activity = ((await response.json()) as { data: Activity }).data
      fixture.activityIds.push(activity.id)
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      expect(activity.activityEndDate).toBeNull()
      expect(activity.activityTimeEnd).toBeNull()
      if (options.allDay) {
        expect(activity.activityTimeStart).toBeNull()
        await expect(page.getByTestId('activity-datetime')).not.toContainText('23:00')
      } else await expect(page.getByTestId('activity-datetime')).toContainText('23:00')
      const persisted = await fixture.detail(activity.id)
      expect(persisted.activityEndDate).toBeNull()
      expect(persisted.activityTimeEnd).toBeNull()
    } finally {
      await fixture.cleanup()
    }
  })
}

test('公開済みの差分を mobile 390/360 と keyboard で確認・選択する', async ({ page }, testInfo) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  try {
    const id = await fixture.schedule(`実機公開同期-${Date.now()}`)
    const activity = await fixture.published(`実機公開値-${Date.now()}`, id)
    const before = await fixture.detail(activity.id)
    for (const width of [390, 360]) {
      await page.setViewportSize({ width, height: 844 })
      await schedule(page, scope, id)
      const title = `実機公開選択-${width}-${Date.now()}`
      await editScheduleTitle(page, title)
      await expect(page.getByTestId('activity-sync-apply')).toBeVisible()
      expect((await fixture.detail(activity.id)).title).toBe(
        width === 390 ? before.title : activity.title,
      )
      const checkbox = page.locator(`[id="sync-${activity.id}-title"]`)
      await checkbox.focus()
      await page.keyboard.press('Space')
      await expect(checkbox).toBeChecked()
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > window.innerWidth,
      )
      expect(overflow, `${width}px 画面全体は横パンしない`).toBe(false)
      for (const key of [
        'activity-sync-cancel',
        'activity-sync-schedule-only',
        'activity-sync-apply',
      ]) {
        const box = await page.getByTestId(key).boundingBox()
        expect(box?.height).toBeGreaterThanOrEqual(44)
        expect(box?.width).toBeGreaterThanOrEqual(44)
      }
      await testInfo.attach(`公開済み差分-${width}px`, {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      })
      await page.getByTestId('activity-sync-apply').focus()
      await mutation(page, `/schedules/${id}`, () => page.keyboard.press('Enter'))
      activity.title = title
      await detail(page, activity)
      const after = await fixture.detail(activity.id)
      expect(after.status).toBe('PUBLISHED')
      expect(after.description).toBe(before.description)
      expect(after.fieldValues).toBe(before.fieldValues)
    }
  } finally {
    await fixture.cleanup()
  }
})

test('元予定が中止・削除されても記録本文を保持し参照状態を画面で示す', async ({
  page,
}, testInfo) => {
  await signIn(page)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  try {
    const id = await fixture.schedule(`実機参照元-${Date.now()}`)
    const activity = await fixture.published(`実機残す記録-${Date.now()}`, id)
    await detail(page, activity)
    await page.getByTestId('activity-source-schedule').click()
    await expect(page).toHaveURL(new RegExp(`eventId=${id}`))
    // 中止状態のみ API fixture。中止操作の UI 導線は既存予定画面にない。
    await success(await page.request.post(`${fixture.schedules}/${id}/cancel`))
    await detail(page, activity)
    await expect(page.getByText('元の予定は中止されています', { exact: true })).toBeVisible()
    await expect(page.getByTestId('activity-source-schedule')).toBeVisible()
    await schedule(page, scope, id)
    page.once('dialog', (dialog) => dialog.accept())
    const [deleted] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'DELETE' &&
          new URL(response.url()).pathname.endsWith(`/schedules/${id}`),
      ),
      page
        .locator('button:visible')
        .filter({ has: page.locator('.pi-trash') })
        .click(),
    ])
    expect(deleted.status()).toBe(204)
    fixture.scheduleIds.splice(fixture.scheduleIds.indexOf(id), 1)
    await detail(page, activity)
    await expect(page.getByTestId('activity-source-schedule')).toHaveCount(0)
    await expect(page.getByText('元の予定は閲覧できません', { exact: true })).toBeVisible()
    await expect(page.getByTestId('activity-description')).toContainText('保持する活動本文')
    await testInfo.attach('参照元削除後の保持記録', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    })
    expect((await fixture.detail(activity.id)).description).toBe(activity.description)
  } finally {
    await fixture.cleanup()
  }
})

test('予定のみ編集権の MEMBER が予定を更新しても他作者の記録を上書きしない', async ({
  page,
  browser,
}) => {
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
    await expect(
      editor.locator(`[data-testid="schedule-activity-${activity.id}"]:visible`),
    ).toBeVisible()
    await mutation(editor, `/schedules/${id}`, () =>
      editScheduleTitle(editor!, `実機予定担当-${Date.now()}`),
    )
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

test('通常一覧の下書き保存から詳細へ進み必須実参加者を選択して公開する', async ({ page }) => {
  await signIn(page, TEAM_ADMIN)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  try {
    const title = `実機通常下書き-${Date.now()}`
    const templateName = `実機通常テンプレ-${Date.now()}`
    await fixture.template(templateName, true)
    await page.goto(`${scope.path}/activities`)
    await page.getByTestId('activity-add-record').click()
    await expect(page.getByTestId('activity-create-dialog')).toBeVisible()
    await page.getByTestId('activity-template-select').click()
    await page.getByRole('option', { name: templateName, exact: true }).click()
    await page.getByTestId('activity-title-input').fill(title)
    const date = page.getByTestId('activity-date-input').locator('input')
    await date.fill('2026/10/15')
    await date.press('Tab')
    await page.getByTestId('activity-description-input').fill('通常下書きの本文')
    const [response] = await Promise.all([
      page.waitForResponse(
        (res) =>
          new URL(res.url()).pathname === '/api/v1/activities/draft' &&
          res.request().method() === 'POST',
      ),
      page.getByTestId('activity-save-draft').click(),
    ])
    expect(response.status()).toBe(201)
    const record = ((await response.json()) as { data: { id: number } }).data
    fixture.activityIds.push(record.id)
    await expect(page).toHaveURL(new RegExp(`/activities/${record.id}$`))
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await expect(page.getByTestId('activity-description')).toContainText('通常下書きの本文')
    await expect(page.getByTestId('activity-source-schedule')).toHaveCount(0)
    const persisted = await fixture.detail(record.id)
    expect(persisted.status).toBe('DRAFT')
    expect(persisted.scopePublicId).toBe(scope.slug)
    await page.getByTestId('activity-edit-draft').click()
    await page.getByTestId('activity-edit-participants').click()
    await page.getByRole('option', { name: '田中太郎', exact: true }).click()
    await page.keyboard.press('Escape')
    await mutation(page, `/activities/${record.id}`, () =>
      page.getByTestId('activity-edit-save').click(),
    )
    await expect(page.getByTestId('activity-participants')).toContainText('田中太郎')
    await mutation(page, `/activities/${record.id}/publish`, () =>
      page.getByTestId('activity-publish').click(),
    )
    await expect(page.getByTestId('activity-publish')).toHaveCount(0)
    const published = await fixture.detail(record.id)
    expect(published.status).toBe('PUBLISHED')
    expect(published.participants).toHaveLength(1)
    await page.getByRole('button', { name: '活動記録', exact: true }).click()
    await expect(page).toHaveURL(`${scope.path}/activities`)
  } finally {
    await fixture.cleanup()
  }
})

test('実 MinIO 添付を詳細画面から開けて他scope・非所属は直接 ID でも拒否される', async ({
  page,
  browser,
}, testInfo) => {
  await signIn(page, TEAM_ADMIN)
  const scope = await resolveScope('TEAM')
  const fixture = new ActivitySyncFixture(page, scope)
  const pages: Page[] = []
  try {
    const name = `activity-sync-${Date.now()}.txt`
    const content = '活動記録 添付の実物を確認'
    const fileId = await fixture.attachment(name, content)
    const activity = await fixture.published(`実機添付-${Date.now()}`, undefined, [fileId])
    const reader = await rolePage(browser, READER)
    pages.push(reader)
    await reader.goto(`${scope.path}/activities`)
    await reader.getByTestId(`activity-detail-${activity.id}`).click()
    const button = reader.getByRole('button', { name, exact: true })
    await expect(button).toBeVisible()
    await expect(button).toBeEnabled()
    const opened = Promise.race([
      reader
        .context()
        .waitForEvent('page')
        .then((openedPage) => ({ kind: 'page' as const, page: openedPage })),
      reader.waitForEvent('download').then((download) => ({ kind: 'download' as const, download })),
    ])
    const [response, result] = await Promise.all([
      reader.waitForResponse(
        (res) => new URL(res.url()).pathname === `/api/v1/files/${fileId}/download-url`,
      ),
      opened,
      button.click(),
    ])
    expect(response.ok()).toBeTruthy()
    const downloadUrl = new URL(
      ((await response.json()) as { data: { downloadUrl: string } }).data.downloadUrl,
    )
    expect(['http://localhost:19010', 'http://127.0.0.1:19010']).toContain(downloadUrl.origin)
    expect(downloadUrl.pathname.split('/')[1]).toBe('cmp2610071510-storage')
    if (result.kind === 'page') {
      await expect(result.page.locator('body')).toContainText(content)
      await testInfo.attach('実物添付の表示', {
        body: await result.page.screenshot(),
        contentType: 'image/png',
      })
      await result.page.close()
    } else {
      expect(await result.download.failure()).toBeNull()
      const output = testInfo.outputPath(name)
      await result.download.saveAs(output)
      expect(await readFile(output, 'utf8')).toBe(content)
    }
    for (const email of [OTHER_TENANT, OUTSIDER]) {
      const denied = await rolePage(browser, email)
      pages.push(denied)
      await denied.goto(`/activities/${activity.id}`)
      await expect(denied.getByTestId('load-error-state')).toBeVisible()
      await expect(denied.getByRole('button', { name, exact: true })).toHaveCount(0)
      // UI拒否に加え、URL直打ち相当の実 HTTP で既存ファイル API の認可を観測する。
      for (const suffix of ['', '/download-url']) {
        const blocked = await denied.request.get(`${API}/files/${fileId}${suffix}`)
        expect([403, 404], `別scope/非所属の直接ファイル ID: ${blocked.status()}`).toContain(
          blocked.status(),
        )
      }
    }
  } finally {
    for (const member of pages) await member.context().close()
    await fixture.cleanup()
  }
})
