import { test, expect, type Page } from '@playwright/test'
import { defaultOptions } from 'primevue/config'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-261007-2053: PrimeVue 既定文言（aria-label・日付・ページ送り）の言語追従（E2E）。
 *
 * 前提とする検証用ページ（出陣で作成）: /e2e/primevue-locale（app/pages/e2e/primevue-locale.vue）
 * - auth: false。本番ビルドでは 404（開発・E2E 環境のみ有効）
 * - data-testid="pv-dialog"     : <Dialog visible appendTo="self" closable :modal="false"> 常時表示
 * - data-testid="pv-drawer"     : <Drawer visible appendTo="self" position="right" :modal="false"> 常時表示
 *   （Drawer パネルは body 直下に fixed 描画されるため、左上の言語切替ボタンを覆わないよう右端・幅 16rem に置く）
 * - data-testid="pv-tabs"       : 幅を狭めた <Tabs scrollable>（タブ多数で送りボタンが出る）
 * - data-testid="pv-datepicker" : <DatePicker inline showButtonBar>
 * - data-testid="pv-paginator"  : <Paginator :rows="10" :totalRecords="100">
 * - data-testid="apply-locale-{code}" ボタン: useLocale().applyAccountLocale(code) を await し、
 *   完了後 data-testid="apply-locale-done" のテキストを code にする
 */

test.use({ storageState: { cookies: [], origins: [] } })

const PAGE_PATH = '/e2e/primevue-locale'
const EN = defaultOptions.locale as unknown as {
  today: string
  prevMonth: string
  nextMonth: string
  aria: { close: string; next: string; previous: string; nextPageLabel: string; prevPageLabel: string }
}

async function setLocaleCookie(page: Page, baseURL: string | undefined, lang: string) {
  if (!baseURL) throw new Error('locale Cookie 試験には baseURL が必要です')
  await page.context().addCookies([
    { name: 'i18n_locale', value: lang, url: new URL('/', baseURL).href, sameSite: 'Lax' },
  ])
}

test.describe('CMP-261007-2053 PrimeVue 既定文言の言語追従', () => {
  test('AC-5: Cookie=de の SSR HTML で常時表示 Dialog の閉じるボタン aria-label が de（Schließen）', async ({ request }) => {
    const res = await request.get(PAGE_PATH, { headers: { Cookie: 'i18n_locale=de' } })
    expect(res.status()).toBe(200)
    const html = await res.text()
    const closeButton = html.match(/<button[^>]*class="[^"]*p-dialog-close-button[^"]*"[^>]*>/)
    expect(closeButton, 'SSR HTML に Dialog の閉じるボタンがある').not.toBeNull()
    expect(closeButton?.[0]).toContain('aria-label="Schließen"')
    expect(closeButton?.[0]).not.toContain(`aria-label="${EN.aria.close}"`)
  })

  test('AC-5: Cookie=ja の SSR HTML でも Dialog の閉じるボタン aria-label は英語既定ではない', async ({ request }) => {
    const res = await request.get(PAGE_PATH, { headers: { Cookie: 'i18n_locale=ja' } })
    expect(res.status()).toBe(200)
    const html = await res.text()
    const closeButton = html.match(/<button[^>]*class="[^"]*p-dialog-close-button[^"]*"[^>]*>/)
    expect(closeButton?.[0]).toContain('aria-label="閉じる"')
  })

  test('AC-6: applyAccountLocale の完了＋描画反映後、再読み込みなしで Dialog/Drawer close・Tabs 送り・DatePicker・Paginator が新言語になる', async ({ page, baseURL }) => {
    await setLocaleCookie(page, baseURL, 'en')
    await page.goto(PAGE_PATH)
    await waitForHydration(page)

    const dialogClose = page.getByTestId('pv-dialog').locator('.p-dialog-close-button')
    // Drawer は append-to="self" を指定しても Portal で body 直下へ描画されるため、
    // pv-drawer ラッパ内ではなくページ全体から Drawer ルート（.p-drawer）配下の閉じるボタンを特定する。
    // Dialog の閉じるボタンは .p-dialog-close-button で別クラスのため取り違えない（strict mode で一意性も担保）
    const drawerClose = page.locator('.p-drawer .p-drawer-close-button')
    const tabsNext = page.getByTestId('pv-tabs').locator('.p-tablist-next-button')
    const datePrev = page.getByTestId('pv-datepicker').locator('.p-datepicker-prev-button')
    const dateNext = page.getByTestId('pv-datepicker').locator('.p-datepicker-next-button')
    const todayButton = page.getByTestId('pv-datepicker').locator('.p-datepicker-buttonbar button').first()
    const pageNext = page.getByTestId('pv-paginator').locator('.p-paginator-next')
    const pagePrev = page.getByTestId('pv-paginator').locator('.p-paginator-prev')

    // 開始時は en（英語既定どおり）であることを確認してから切り替える
    await expect(dialogClose).toHaveAttribute('aria-label', EN.aria.close)

    await page.evaluate(() => {
      ;(window as unknown as { __cmp2053NoReload?: boolean }).__cmp2053NoReload = true
    })
    await page.getByTestId('apply-locale-de').click()
    await expect(page.getByTestId('apply-locale-done')).toHaveText('de', { timeout: 60_000 })

    await expect(dialogClose).toHaveAttribute('aria-label', 'Schließen')
    await expect(drawerClose).toHaveAttribute('aria-label', 'Schließen')
    await expect(tabsNext).toHaveAttribute('aria-label', /.+/)
    await expect(tabsNext).not.toHaveAttribute('aria-label', EN.aria.next)
    await expect(datePrev).not.toHaveAttribute('aria-label', EN.prevMonth)
    await expect(dateNext).not.toHaveAttribute('aria-label', EN.nextMonth)
    await expect(todayButton).not.toHaveText(EN.today)
    await expect(pageNext).not.toHaveAttribute('aria-label', EN.aria.nextPageLabel)
    await expect(pagePrev).not.toHaveAttribute('aria-label', EN.aria.prevPageLabel)

    // 再読み込みしていない（window 上の印が残っている）
    expect(
      await page.evaluate(() => (window as unknown as { __cmp2053NoReload?: boolean }).__cmp2053NoReload),
    ).toBe(true)

    // さらに de→en と戻すと、前言語（de）の値が残らず英語既定に戻る
    await page.getByTestId('apply-locale-en').click()
    await expect(page.getByTestId('apply-locale-done')).toHaveText('en', { timeout: 60_000 })
    await expect(dialogClose).toHaveAttribute('aria-label', EN.aria.close)
    await expect(drawerClose).toHaveAttribute('aria-label', EN.aria.close)
    await expect(pageNext).toHaveAttribute('aria-label', EN.aria.nextPageLabel)
  })
})
