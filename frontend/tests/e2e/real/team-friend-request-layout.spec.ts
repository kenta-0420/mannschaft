import { readFileSync, writeFileSync } from 'node:fs'
import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'
import { API_BASE, FriendRequestFixture, openFriendActor, type FriendActor, type FriendTeam } from './helpers/team-friend-request-fixture'

/** CMP-261024-2051: 実API・実DBの長名候補を実UIで検索し、多言語の文字欠けを検査する。 */
test.describe.configure({ mode: 'default' })
test.use({ storageState: { cookies: [], origins: [] } })
let actor: FriendActor
let fixture: FriendRequestFixture
let source: FriendTeam
let target: FriendTeam
const locales = [
  { code: 'ja', name: '日本語' }, { code: 'en', name: 'English' },
  { code: 'zh', name: '中文（简体）' }, { code: 'ko', name: '한국어' },
  { code: 'es', name: 'Español' }, { code: 'de', name: 'Deutsch' },
]
const laterLabels = locales.map(({ code }) => {
  const messages = JSON.parse(readFileSync(new URL('../../../app/locales/' + code + '/scope_guide.json', import.meta.url), 'utf8')) as { scopeGuide: { setup: { later: string } } }
  return messages.scopeGuide.setup.later
})

async function open(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
}
async function language(page: Page, name: string, code: string): Promise<void> {
  await open(page, '/settings/language')
  await page.getByRole('combobox').first().click()
  await page.getByRole('option', { name, exact: true }).click()
  const saved = page.waitForResponse(response => response.request().method() === 'PUT' && new URL(response.url()).pathname === '/api/v1/users/me')
  await page.locator('button[translate="no"]').click()
  expect((await saved).status()).toBe(200)
  await expect.poll(async () => {
    const response = await page.request.get(API_BASE + '/api/v1/users/me')
    expect(response.status()).toBe(200)
    return (await response.json()).data.locale as string
  }).toBe(code)
}
async function stable(locator: Locator): Promise<void> {
  await locator.page().evaluate(async () => { await document.fonts.ready })
  await expect.poll(() => locator.evaluate(element => {
    for (let ancestor: Element | null = element; ancestor; ancestor = ancestor.parentElement) {
      if (ancestor.getAnimations({ subtree: false }).some(animation => animation.pending || animation.playState === 'running')) return false
    }
    return true
  })).toBe(true)
  await expect.poll(() => locator.evaluate(async element => {
    const rect = () => { const box = element.getBoundingClientRect(); return [box.x, box.y, box.width, box.height] }
    const first = JSON.stringify(rect())
    for (let frame = 0; frame < 3; frame++) {
      await new Promise<void>(resolve => requestAnimationFrame(() => resolve()))
      if (JSON.stringify(rect()) !== first) return false
    }
    return true
  })).toBe(true)
}
async function evidence(page: Page, info: TestInfo, name: string, metrics: unknown): Promise<void> {
  const json = info.outputPath(name + '.json')
  writeFileSync(json, JSON.stringify(metrics, null, 2), 'utf8')
  await info.attach(name, { path: json, contentType: 'application/json' })
  const screenshot = info.outputPath(name + '.png')
  await page.screenshot({ path: screenshot })
  await info.attach(name + '-screenshot', { path: screenshot, contentType: 'image/png' })
}

async function textGeometry(locator: Locator) {
  return locator.evaluate(element => {
    const box = element.getBoundingClientRect()
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
    const fragments: { text: string; left: number; right: number; top: number; bottom: number }[] = []
    while (walker.nextNode()) {
      const node = walker.currentNode
      if (!node.textContent?.trim()) continue
      const range = document.createRange()
      range.selectNodeContents(node)
      for (const rect of Array.from(range.getClientRects())) fragments.push({ text: node.textContent, left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom })
    }
    const clipped = fragments.filter(fragment => {
      for (let ancestor: Element | null = element; ancestor; ancestor = ancestor.parentElement) {
        const style = getComputedStyle(ancestor)
        const rect = ancestor.getBoundingClientRect()
        if (['hidden', 'clip', 'auto', 'scroll'].includes(style.overflowX) && (fragment.left < rect.left - 1 || fragment.right > rect.right + 1)) return true
        if (['hidden', 'clip', 'auto', 'scroll'].includes(style.overflowY) && (fragment.top < rect.top - 1 || fragment.bottom > rect.bottom + 1)) return true
      }
      return false
    })
    return { box: { left: box.left, right: box.right, width: box.width, height: box.height }, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, fragments, clipped }
  })
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000)
  actor = await openFriendActor(browser, 'SOURCE')
  await actor.page.addLocatorHandler(actor.page.getByTestId('member-permission-setup'), async dialog => {
    const mask = actor.page.locator('.p-dialog-mask').filter({ has: dialog })
    const postpone = laterLabels.map(label => dialog.getByRole('button', { name: label, exact: true })).reduce((union, button) => union.or(button))
    await postpone.click()
    await expect(dialog).not.toBeVisible()
    await expect(mask).not.toBeVisible()
  })
  fixture = new FriendRequestFixture()
  source = await fixture.team(actor, '表示検証元')
  target = await fixture.team(actor, '長名候補')
  const longName = ('Internationale ' + target.slug + ' Freundschaft und Zusammenarbeit Gemeinschaft für gemeinsame Sportveranstaltungen ').slice(0, 100).padEnd(100, 'x')
  expect(longName).toHaveLength(100)
  const renamed = await actor.page.request.put(API_BASE + '/api/v1/teams/' + target.slug, { data: { name: longName } })
  expect(renamed.status(), '上限100文字の前提チーム名を保存').toBe(200)
  target.name = longName
})
test.afterAll(async () => {
  try { if (fixture) await fixture.cleanup() }
  finally { if (actor) await actor.context.close() }
})

for (const locale of locales) {
  for (const width of [360, 390]) {
    test('FR-LAYOUT-TABS: ' + locale.code + ' ' + width + 'pxでタブ全文を自コンテナ内で読める', async ({ browserName: _browserName }, info) => {
      test.setTimeout(90_000)
      const page = actor.page
      await page.setViewportSize({ width, height: 844 })
      await language(page, locale.name, locale.code)
      await open(page, '/teams/' + source.slug + '/nav')
      const tabs = page.getByTestId('friend-list-tabs')
      await expect(tabs).toBeVisible()
      await stable(tabs)
      const buttons = tabs.getByRole('button')
      await expect(buttons).toHaveCount(3)
      for (let index = 0; index < 3; index++) {
        const button = buttons.nth(index)
        await button.scrollIntoViewIfNeeded()
        const metrics = await textGeometry(button)
        await evidence(page, info, 'tab-' + index, metrics)
        expect.soft(metrics.box.height, 'モバイルのタブ操作領域は44px以上').toBeGreaterThanOrEqual(44)
        expect.soft(metrics.box.width, 'モバイルのタブ操作領域は44px以上').toBeGreaterThanOrEqual(44)
        expect.soft(metrics.scrollWidth, 'タブボタン自身でラベルが圧縮・欠落しない').toBeLessThanOrEqual(metrics.clientWidth + 1)
        expect.soft(metrics.clipped, '自コンテナ内をスクロールしたタブの全文が表示される').toEqual([])
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    })
    test('FR-LAYOUT-CANDIDATE: ' + locale.code + ' ' + width + 'pxで長名候補とslugを画面内で読める', async ({ browserName: _browserName }, info) => {
      test.setTimeout(90_000)
      const page = actor.page
      await page.setViewportSize({ width, height: 844 })
      await language(page, locale.name, locale.code)
      await open(page, '/teams/' + source.slug + '/nav')
      await page.getByTestId('friend-follow-button').click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await stable(dialog)
      const keyword = 'Internationale ' + target.slug
      const result = page.waitForResponse(response => response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/v1/teams/search' && new URL(response.url()).searchParams.get('keyword') === keyword)
      await page.locator('#friend-follow-search').fill(keyword)
      expect((await result).status()).toBe(200)
      const candidate = page.getByTestId('friend-follow-candidate').filter({ hasText: target.slug })
      await expect(candidate).toBeVisible()
      await expect(candidate).toContainText(target.name)
      await stable(candidate)
      const option = page.getByRole('option').filter({ has: candidate })
      const metrics = await textGeometry(option)
      const dialogBox = await dialog.boundingBox()
      expect(dialogBox).not.toBeNull()
      await evidence(page, info, 'long-candidate', { ...metrics, dialogBox, name: target.name, slug: target.slug, width })
      expect.soft(metrics.box.left).toBeGreaterThanOrEqual(0)
      expect.soft(metrics.box.right, 'portal候補がviewport右端を越えない').toBeLessThanOrEqual(width)
      expect.soft(metrics.box.right, '候補をダイアログの幅に収める').toBeLessThanOrEqual(dialogBox!.x + dialogBox!.width + 1)
      expect.soft(metrics.clipped, 'チーム名とslugの文字が候補内で欠落しない').toEqual([])
      for (const fragment of metrics.fragments) {
        expect.soft(fragment.left).toBeGreaterThanOrEqual(0)
        expect.soft(fragment.right, '折り返された文字列が画面内にある').toBeLessThanOrEqual(width)
      }
      await candidate.click()
      await expect(dialog.getByTestId('friend-request-direction')).toContainText(target.slug)
      await page.keyboard.press('Escape')
      await expect(dialog).not.toBeVisible()
    })
    test('FR-LAYOUT-SOURCE: ' + locale.code + ' ' + width + 'pxで申請元の選択領域が44px以上ある', async ({ browserName: _browserName }, info) => {
      test.setTimeout(90_000)
      const page = actor.page
      await page.setViewportSize({ width, height: 844 })
      await language(page, locale.name, locale.code)
      await open(page, '/public/teams/' + target.slug)
      await page.getByTestId('friend-request-entry').click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      const sourceSelect = page.getByTestId('friend-request-source')
      await expect(sourceSelect).toBeVisible()
      await sourceSelect.click()
      await page.getByRole('option').filter({ hasText: source.slug }).click()
      await expect(sourceSelect).toContainText(source.slug)
      await stable(sourceSelect)
      const metrics = await sourceSelect.evaluate(element => {
        const box = element.getBoundingClientRect()
        return { width: box.width, height: box.height, minHeight: getComputedStyle(element).minHeight }
      })
      await evidence(page, info, 'source-select', metrics)
      expect.soft(metrics.height, 'モバイルの申請元選択領域は44px以上').toBeGreaterThanOrEqual(44)
      expect.soft(metrics.width, 'モバイルの申請元選択領域は44px以上').toBeGreaterThanOrEqual(44)
      await page.keyboard.press('Escape')
      await expect(dialog).not.toBeVisible()
    })
  }
}
