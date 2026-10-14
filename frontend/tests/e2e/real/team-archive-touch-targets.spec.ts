import { randomUUID } from 'node:crypto'
import { test, expect, errors, type Locator, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-261011-0035: 実ブラウザの操作領域を測る。CSSクラスの有無では代替しない。
 * 各ケースが本人ADMINの専用チームを作成し、休止/再開は実UIで行う。
 * APIモックなし。前提作成・所有チームのcleanup以外にAPI直接操作を使わない。
 */
const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8080'
test.use({ storageState: { cookies: [], origins: [] }, hasTouch: true, isMobile: true })
test.setTimeout(120_000)

async function measureTarget(target: Locator, label: string) {
  await expect(target, label).toBeVisible()
  // Popup enterのscale変換は子のbboxにも乗る。祖先を含む有限animation完了を待つ。
  await expect.poll(() => target.evaluate((element) => {
    let current: Element | null = element
    while (current) {
      if (current.getAnimations().some(animation =>
        animation.effect?.getTiming().iterations !== Infinity
        && animation.playState !== 'finished' && animation.playState !== 'idle')) return false
      current = current.parentElement
    }
    return true
  }), { message: `${label}の表示animation完了` }).toBe(true)
  const box = await target.boundingBox()
  expect(box, `${label}に実レイアウト領域がある`).not.toBeNull()
  await test.info().attach(label, {
    body: JSON.stringify({ width: box!.width, height: box!.height }),
    contentType: 'application/json',
  })
  expect.soft(box!.width, `${label}のタップ領域幅`).toBeGreaterThanOrEqual(44)
  expect.soft(box!.height, `${label}のタップ領域高さ`).toBeGreaterThanOrEqual(44)
}

async function measureConfirmation(page: Page, label: string) {
  const dialog = page.getByRole('alertdialog', { name: label, exact: true })
  await expect(dialog).toBeVisible()
  await expect(page.getByRole('alertdialog'), '確認画面が二重表示されない').toHaveCount(1)
  await measureTarget(dialog.getByRole('button', { name: label, exact: true }), `${label}-確定`)
  await measureTarget(dialog.getByRole('button', { name: 'キャンセル', exact: true }), `${label}-キャンセル`)
  await measureTarget(dialog.locator('button[data-pc-name="pcclosebutton"]'), `${label}-閉じる`)
  return dialog
}

for (const viewport of [{ width: 390, height: 844 }, { width: 360, height: 800 }]) {
  test(`${viewport.width}px: 休止メニューと休止/再開確認の実タップ領域が44x44以上`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await loginViaApi(page, {
      email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
      password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
    }, { apiBaseUrl: apiBase })

    const slug = `touch-${randomUUID().slice(0, 12)}`
    const name = `ArchiveTouch-${slug}`
    const created = await page.request.post(`${apiBase}/api/v1/teams`, {
      data: { name, slug, template: 'GENERAL', visibility: 'PUBLIC' },
    })
    expect(created.status(), '本人の専用チーム作成').toBe(201)
    const team = (await created.json()).data as { numericId: number; slug: string; basicInfo: { name: string } }
    expect(team.slug).toBe(slug)
    expect(team.basicInfo.name).toBe(name)
    expect(team.numericId).toBeGreaterThan(0)
    await test.info().attach('専用チーム', {
      body: JSON.stringify({ id: team.numericId, slug, name }), contentType: 'application/json',
    })

    try {
      await page.goto(`/teams/${slug}`)
      await waitForHydration(page)
      const postpone = page.getByRole('button', { name: 'あとで決める', exact: true })
      // 初期権限dialogはhydration後に遅延表示される。未表示の正常系だけを許容する。
      try {
        await postpone.waitFor({ state: 'visible', timeout: 5_000 })
        await postpone.tap()
        await expect(postpone).toBeHidden()
      }
      catch (error) {
        if (!(error instanceof errors.TimeoutError)) throw error
      }
      const more = page.getByRole('button', { name: 'その他の操作', exact: true })
      await expect(more).toBeVisible({ timeout: 30_000 })
      await more.tap()

      const entry = page.getByRole('menuitem', { name: 'チームをアーカイブ', exact: true })
      // installed PrimeVue Menuitemの実clickハンドラはitemContentに付く。
      const hitArea = entry.locator('[data-pc-section="itemcontent"]')
      await measureTarget(hitArea, '活動休止メニュー')
      await hitArea.tap()

      let dialog = await measureConfirmation(page, 'チームをアーカイブ')
      // キャンセルの実操作でも閉じることを確認。休止状態は変えない。
      await dialog.getByRole('button', { name: 'キャンセル', exact: true }).tap()
      await expect(dialog).toBeHidden()
      await more.tap()
      await entry.locator('[data-pc-section="itemcontent"]').tap()
      dialog = page.getByRole('alertdialog', { name: 'チームをアーカイブ', exact: true })
      await page.keyboard.press('Escape')
      await expect(dialog).toBeHidden()
      await more.tap()
      await entry.locator('[data-pc-section="itemcontent"]').tap()
      await dialog.locator('button[data-pc-name="pcclosebutton"]').tap()
      await expect(dialog).toBeHidden()
      await more.tap()
      await entry.locator('[data-pc-section="itemcontent"]').tap()
      const archived = page.waitForResponse(response =>
        new URL(response.url()).pathname === `/api/v1/teams/${slug}/archive`
        && response.request().method() === 'PATCH')
      await dialog.getByRole('button', { name: 'チームをアーカイブ', exact: true }).tap()
      expect((await archived).status()).toBe(200)
      await page.waitForURL('**/teams')

      await page.getByTestId('archived-teams-toggle').tap()
      const row = page.locator('li').filter({ hasText: name })
        .filter({ has: page.getByTestId('team-unarchive-button') })
      await expect(row).toBeVisible()
      await row.getByTestId('team-unarchive-button').tap()
      dialog = await measureConfirmation(page, 'アーカイブを解除')
      await dialog.getByRole('button', { name: 'キャンセル', exact: true }).tap()
      await expect(dialog).toBeHidden()
      await expect(row).toBeVisible()

      await row.getByTestId('team-unarchive-button').tap()
      dialog = page.getByRole('alertdialog', { name: 'アーカイブを解除', exact: true })
      const unarchived = page.waitForResponse(response =>
        new URL(response.url()).pathname === `/api/v1/teams/${slug}/unarchive`
        && response.request().method() === 'PATCH')
      await dialog.getByRole('button', { name: 'アーカイブを解除', exact: true }).tap()
      expect((await unarchived).status()).toBe(200)
      await expect(row).toBeHidden()
    }
    finally {
      // 自分がこのケースで作成した固定slug以外をcleanupしない。
      const removed = await page.request.delete(`${apiBase}/api/v1/teams/${slug}`)
      await test.info().attach('専用チームcleanup', {
        body: JSON.stringify({ id: team.numericId, slug, status: removed.status() }),
        contentType: 'application/json',
      })
      expect(removed.status(), '専用チームのcleanup').toBe(204)
    }
  })
}
