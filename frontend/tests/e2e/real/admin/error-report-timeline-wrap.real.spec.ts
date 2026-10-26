import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

/**
 * CMP-261022-0338: 専有DBの実API/実画面を使う回帰。通信モックなし。
 * fixture作成だけAPIを使い、コメントは画面から送信する。
 * 削除APIが無いため、作成reportIdを記録し専有runtime破棄でcleanupする。
 */
test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(300_000)

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`実機専有環境の必須設定がありません: ${name}`)
  return value
}
function credentials(role: string) {
  return { email: required(`TEST_${role}_EMAIL`), password: required(`TEST_${role}_PASSWORD`) }
}
const api = () => required('TIMELINE_REAL_API_BASE')
let reportId: number
let marker: string

test.beforeAll(async ({ request }) => {
  expect(required('TIMELINE_REAL_OWNED_RUNTIME')).toBe('1')
  marker = `CMP-261022-0338-${crypto.randomUUID()}`
  const response = await request.post(`${api()}/api/v1/error-reports`, {
    data: {
      errorMessage: marker,
      pageUrl: 'https://example.test/timeline-wrap',
      occurredAt: '2026-10-26T12:00:00',
    },
  })
  expect(response.status()).toBe(201)
  reportId = (await response.json()).id as number
  expect(Number.isSafeInteger(reportId)).toBe(true)
  console.log(`専有DB cleanup対象: error_reports.id=${reportId}`)
})

async function submitComment(page: Page, content: string) {
  await page.getByRole('tab', { name: '概要', exact: true }).click()
  const input = page.getByPlaceholder('コメントを入力（最大2000文字）')
  await input.fill(content)
  await expect(input).toHaveValue(content)
  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/error-reports/${reportId}/comments`) &&
      response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'コメント追加', exact: true }).click()
  expect((await saved).status()).toBe(200)
  await page.getByRole('tab', { name: 'タイムライン', exact: true }).click()
}

async function measure(page: Page, content: string) {
  return page.evaluate((content) => {
    const el = [
      ...document.querySelectorAll<HTMLElement>('[data-active="true"] .whitespace-pre-wrap'),
    ].find((el) => el.textContent === content)
    if (!el) return null
    const box = el.getBoundingClientRect()
    const range = document.createRange()
    range.selectNodeContents(el)
    const rects = [...range.getClientRects()]
    return {
      width: el.clientWidth,
      scrollWidth: el.scrollWidth,
      lines: rects.length,
      outside: rects.filter(
        (rect) =>
          rect.left < box.left - 1 || rect.right > box.right + 1 || rect.bottom > box.bottom + 1,
      ).length,
    }
  }, content)
}

test('SYSTEM_ADMINが自然導線からコメントを送り、長文を360/390pxで末尾まで読める', async ({
  page,
}, testInfo) => {
  await loginViaApi(page, credentials('ADMIN'), { apiBaseUrl: api(), deferNavigation: true })
  await page.goto('/system-admin')
  await waitForHydration(page, { reloadOnTimeout: false })
  await page.locator('a[href="/system-admin/error-reports"]').first().click()
  await expect(page).toHaveURL(/\/system-admin\/error-reports$/)
  const row = page.getByRole('row').filter({ hasText: marker })
  await row.getByRole('button', { name: '詳細を開く' }).click()
  await expect(page).toHaveURL(new RegExp(`/system-admin/error-reports/${reportId}$`))

  const comments = [
    ['ASCII2000', 'A'.repeat(2000)],
    ['URL2000', 'https://example.test/' + 'path'.repeat(495)],
    ['混在2000', ('日本語ABChttps://example.test/' + 'x'.repeat(80)).repeat(20).slice(0, 2000)],
    ['改行空行', '先頭行\n\n末尾行'],
  ]
  for (const [label, content] of comments) {
    await submitComment(page, content)
    // タイムラインは取得済み配列を保持するため、再読込で保存後の実データを取得する。
    await page.reload()
    await waitForHydration(page, { reloadOnTimeout: false })
    await page.getByRole('tab', { name: 'タイムライン', exact: true }).click()
    for (const width of [390, 360, 390]) {
      await page.setViewportSize({ width, height: 844 })
      await expect
        .poll(
          async () => {
            const result = await measure(page, content)
            return result !== null && result.outside === 0 && result.scrollWidth <= result.width + 1
          },
          { message: `${label}/${width}の全文Rangeが表示幅内`, timeout: 30_000 },
        )
        .toBe(true)
      console.log(label, width, await measure(page, content))
      const tailVisible = await page.evaluate(async (content) => {
        const scroller = document.querySelector<HTMLElement>('.vue-recycle-scroller')!
        const el = [
          ...scroller.querySelectorAll<HTMLElement>('[data-active="true"] .whitespace-pre-wrap'),
        ].find((el) => el.textContent === content)!
        const range = document.createRange()
        range.setStart(el.firstChild!, content.length - 1)
        range.setEnd(el.firstChild!, content.length)
        scroller.scrollTop +=
          range.getBoundingClientRect().bottom - scroller.getBoundingClientRect().bottom + 20
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        const tail = range.getBoundingClientRect()
        const viewport = scroller.getBoundingClientRect()
        const cards = [
          ...scroller.querySelectorAll<HTMLElement>('.vue-recycle-scroller__item-view'),
        ]
          .filter((el) => el.querySelector('[data-active="true"]'))
          .map((el) => el.querySelector('.rounded-lg')!.getBoundingClientRect())
          .sort((a, b) => a.top - b.top)
        return {
          visible: tail.top >= viewport.top - 1 && tail.bottom <= viewport.bottom + 1,
          overlap: cards.some((box, i) => i > 0 && box.top < cards[i - 1].bottom - 1),
        }
      }, content)
      expect(tailVisible).toEqual({ visible: true, overlap: false })
      await page.screenshot({ path: testInfo.outputPath(`${label}-${width}.png`) })
      await page.locator('.vue-recycle-scroller').evaluate((el) => {
        el.scrollTop = 0
      })
    }
  }
})

for (const [role, label] of [
  ['MEMBER', '一般会員'],
  ['ORG_ADMIN', '組織管理者'],
  ['OTHER_ORG_MEMBER', '別組織会員'],
]) {
  test(`${label}は詳細URLと実APIへアクセスできない`, async ({ page }) => {
    await loginViaApi(page, credentials(role), { apiBaseUrl: api(), deferNavigation: true })
    for (const suffix of ['', '/timeline']) {
      const response = await page.request.get(
        `${api()}/api/v1/system-admin/error-reports/${reportId}${suffix}`,
      )
      expect(response.status()).toBe(403)
      expect(await response.text()).not.toContain(marker)
    }
    const deniedWrite = await page.request.post(
      `${api()}/api/v1/system-admin/error-reports/${reportId}/comments`,
      {
        data: { content: '権限なしの書き込みを拒否する' },
      },
    )
    expect(deniedWrite.status()).toBe(403)
    await page.goto(`/system-admin/error-reports/${reportId}`)
    await waitForHydration(page, { reloadOnTimeout: false })
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.getByRole('tab', { name: 'タイムライン', exact: true })).toHaveCount(0)
    await expect(page.getByPlaceholder('コメントを入力（最大2000文字）')).toHaveCount(0)
  })
}
