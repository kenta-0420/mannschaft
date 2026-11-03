import { test, expect, type Page, type Locator } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-261030-0651: 緊急休業画面の送信履歴・プレビュー表の 390px 崩れ（DOM 寸法 E2E）。
 *
 * 検証専用ページ /e2e/emergency-closure-layout（app/pages/e2e/emergency-closure-layout.vue）を使う。
 * 認証不要・BE 不要（固定データ。送信履歴1件目は理由 200 字・部分時間帯あり、2件目は短い理由・終日）。
 *
 * AC-13: 390x844 送信履歴（折りたたみ時／確認状況展開時を別ケース）
 * AC-14: 390px プレビュー表
 * AC-16: 1440x900 で全列・文字列・ボタンが欠けない
 * （AC-15 の件数タグ i18n は単体 spec で検証する）
 */

test.use({ storageState: { cookies: [], origins: [] } })

const PAGE_PATH = '/e2e/emergency-closure-layout'
const REASON_200 = '院内設備の緊急点検および感染対策のため、'.repeat(11).slice(0, 200)

async function openLayoutPage(page: Page): Promise<void> {
  await page.goto(PAGE_PATH, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(page.getByTestId('layout-history')).toBeVisible()
}

async function box(loc: Locator) {
  const b = await loc.boundingBox()
  if (!b) throw new Error('要素の寸法が取得できない')
  return b
}

/** 行内の見出しが折り返さず切れていない（scrollWidth<=clientWidth かつ 1 行）。 */
async function expectHeaderIntact(th: Locator, label: string) {
  const m = await th.evaluate((el) => {
    const cs = getComputedStyle(el)
    return {
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      height: el.getBoundingClientRect().height,
      lineHeight: parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.5,
      paddingY: parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom),
    }
  })
  expect(m.scrollWidth, `${label} が切れている`).toBeLessThanOrEqual(m.clientWidth)
  expect(m.height - m.paddingY, `${label} が複数行に折り返している (${m.height})`).toBeLessThan(m.lineHeight * 1.6)
}

/** セルが 1 行に収まり（縦に崩れていない）、内容が切れていない。 */
async function expectCellSingleLine(td: Locator, label: string) {
  const m = await td.evaluate((el) => {
    const lh = parseFloat(getComputedStyle(el).lineHeight) || 20
    return { height: el.getBoundingClientRect().height, lh, sw: el.scrollWidth, cw: el.clientWidth }
  })
  expect(m.height, `${label} が縦に崩れている (高さ ${m.height})`).toBeLessThanOrEqual(m.lh * 2 + 24)
  expect(m.sw, `${label} が切れている`).toBeLessThanOrEqual(m.cw)
}

async function expectNoPageOverflow(page: Page, width: number) {
  const sw = await page.evaluate(() => document.documentElement.scrollWidth)
  expect(sw, `ページ全体が横にはみ出している (scrollWidth=${sw})`).toBeLessThanOrEqual(width)
}

async function expectReasonNotVertical(page: Page) {
  const row = page.getByTestId('layout-history').locator('tbody > tr').first()
  const reasonCell = row.locator('td').nth(2)
  const rb = await box(reasonCell)
  expect(rb.width, `理由セルが細すぎる (${rb.width}px)`).toBeGreaterThanOrEqual(200)
  const rowBox = await box(row)
  expect(rowBox.height, `理由 200 字の行が高すぎる (${rowBox.height}px)`).toBeLessThanOrEqual(320)
  // 1 文字ずつ縦並びなら行の高さは 200 字 x 行高(>=20) に達する。1 行あたり 8 文字以上入る幅であること。
  const fontSize = await reasonCell.evaluate((el) => parseFloat(getComputedStyle(el).fontSize))
  expect(Math.floor(rb.width / fontSize), '理由セルの 1 行あたり文字数').toBeGreaterThanOrEqual(8)
}

test.describe('CMP-261030-0651 緊急休業 送信履歴・プレビュー表 390px', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

  test('AC-13a: 送信履歴（折りたたみ時）— 理由が縦崩れせず、見出し・ボタン・ページ幅が健全', async ({ page }) => {
    await openLayoutPage(page)
    const history = page.getByTestId('layout-history')
    await expect(history.getByText(REASON_200)).toBeVisible()

    await expectReasonNotVertical(page)

    const th = history.locator('thead th', { hasText: '確認状況' })
    await expectHeaderIntact(th, '見出し「確認状況」')

    const btn = history.getByRole('button', { name: /確認状況を見る/ }).first()
    const bb = await box(btn)
    expect(bb.width, `「確認状況を見る」幅 ${bb.width}`).toBeGreaterThanOrEqual(44)
    expect(bb.height, `「確認状況を見る」高さ ${bb.height}`).toBeGreaterThanOrEqual(44)

    await expectNoPageOverflow(page, 390)
  })

  test('AC-13b: 送信履歴（確認状況展開時）— 内側の表も縦崩れせず、ページ幅が健全', async ({ page }) => {
    await openLayoutPage(page)
    const history = page.getByTestId('layout-history')
    await history.getByRole('button', { name: /確認状況を見る/ }).first().click()
    const inner = history.locator('table table')
    await expect(inner).toBeVisible()

    await expectReasonNotVertical(page)
    await expectHeaderIntact(history.locator('thead th', { hasText: '確認状況' }).first(), '見出し「確認状況」')

    // 展開後は件数表示ボタンになる。44x44 以上であること。
    const toggle = history.locator('tbody > tr').first().locator('td').last().getByRole('button')
    const tb = await box(toggle)
    expect(tb.width).toBeGreaterThanOrEqual(44)
    expect(tb.height).toBeGreaterThanOrEqual(44)

    // 内側の表: 患者名・予約日時が縦崩れしない／見出し 4 つが切れない
    const innerRows = inner.locator('tbody tr')
    await expect(innerRows).toHaveCount(2)
    for (let i = 0; i < 2; i++) {
      await expectCellSingleLine(innerRows.nth(i).locator('td').nth(0), `患者名[${i}]`)
      await expectCellSingleLine(innerRows.nth(i).locator('td').nth(1), `予約日時[${i}]`)
    }
    const ths = inner.locator('thead th')
    await expect(ths).toHaveCount(4)
    for (let i = 0; i < 4; i++) await expectHeaderIntact(ths.nth(i), `内側見出し[${i}]`)

    await expectNoPageOverflow(page, 390)
  })

  test('AC-14: プレビュー表 — 患者名・日時が縦崩れせず、ページ横はみ出しなし', async ({ page }) => {
    await openLayoutPage(page)
    const preview = page.getByTestId('layout-preview')
    const rows = preview.locator('tbody tr')
    await expect(rows).toHaveCount(2)
    for (let i = 0; i < 2; i++) {
      await expectCellSingleLine(rows.nth(i).locator('td').nth(0), `プレビュー患者名[${i}]`)
      await expectCellSingleLine(rows.nth(i).locator('td').nth(1), `プレビュー日時[${i}]`)
      const rb = await box(rows.nth(i))
      expect(rb.height, `プレビュー行[${i}] が高すぎる (${rb.height}px)`).toBeLessThanOrEqual(64)
    }
    const ths = preview.locator('thead th')
    await expect(ths).toHaveCount(3)
    for (let i = 0; i < 3; i++) await expectHeaderIntact(ths.nth(i), `プレビュー見出し[${i}]`)
    await expectNoPageOverflow(page, 390)
  })
})

test.describe('CMP-261030-0651 緊急休業 送信履歴・プレビュー表 1440px', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('AC-16: 全列・文字列・ボタンが欠けず表示され、横スクロールも出ない', async ({ page }) => {
    await openLayoutPage(page)
    const history = page.getByTestId('layout-history')
    const preview = page.getByTestId('layout-preview')

    // 履歴 5 列の見出し
    const labels = ['送信日時', '休業期間', '理由', '件数', '確認状況']
    const ths = history.locator('thead th')
    await expect(ths).toHaveCount(5)
    for (let i = 0; i < 5; i++) {
      await expect(ths.nth(i)).toHaveText(labels[i]!)
      await expectHeaderIntact(ths.nth(i), `履歴見出し[${labels[i]}]`)
    }

    // 1 行目: 理由全文・件数タグ・期間・時間帯・ボタン
    const row = history.locator('tbody > tr').first()
    await expect(row.locator('td').nth(2)).toHaveText(REASON_200)
    await expect(row.locator('td').nth(3)).toHaveText('12件')
    await expect(row.locator('td').nth(1)).toContainText('2026年11月10日(火)〜2026年11月12日(木)')
    await expect(row.locator('td').nth(1)).toContainText('09:00〜12:00')
    const btn = row.getByRole('button', { name: /確認状況を見る/ })
    await expect(btn).toBeVisible()
    const bb = await box(btn)
    expect(bb.width).toBeGreaterThanOrEqual(44)
    expect(bb.height).toBeGreaterThanOrEqual(44)
    expect((await box(row)).height, '1440px で理由行が高すぎる').toBeLessThanOrEqual(240)

    // 履歴の表は横スクロールを要さない
    const scrollable = await history.locator('table').first().evaluate((el) => {
      const c = el.parentElement!
      return { sw: c.scrollWidth, cw: c.clientWidth }
    })
    expect(scrollable.sw).toBeLessThanOrEqual(scrollable.cw)

    // 展開: 内側 4 列と中身
    await btn.click()
    const inner = history.locator('table table')
    await expect(inner.locator('thead th')).toHaveText(['患者名', '予約日時', '確認', 'リマインド'])
    const r0 = inner.locator('tbody tr').nth(0)
    await expect(r0.locator('td').nth(0)).toHaveText('山田 太郎')
    await expect(r0.locator('td').nth(2)).toContainText('確認済')
    await expect(r0.locator('td').nth(3)).not.toBeEmpty()
    await expect(inner.locator('tbody tr').nth(1).locator('td').nth(0)).toHaveText('鈴木 花子')

    // プレビュー 3 列
    await expect(preview.locator('thead th')).toHaveText(['患者名', '日時', 'ステータス'])
    const p0 = preview.locator('tbody tr').nth(0)
    await expect(p0.locator('td').nth(0)).toHaveText('山田 太郎')
    await expect(p0.locator('td').nth(1)).toContainText('2026年11月10日(火) 09:30〜10:00')
    await expect(p0.locator('td').nth(2)).toHaveText('CONFIRMED')

    await expectNoPageOverflow(page, 1440)
  })
})
