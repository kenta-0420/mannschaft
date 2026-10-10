import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-261007-2053: 404/エラーページ（app/error.vue）の言語追従と情報秘匿（E2E）。
 *
 * 前提とする実装:
 * - app/error.vue: data-testid="error-page" / "error-page-title" / "error-page-description" / "error-page-home"
 * - 文言は common.json の error_page.*（6 言語）
 * - SSR でも Cookie i18n_locale の言語で描画し、<html lang> も同じ言語にする
 */

test.use({ storageState: { cookies: [], origins: [] } })

const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
type Lang = (typeof LANGS)[number]

const NOT_EXIST_PATH = '/no-such-page-cmp-261007-2053'
// 存在秘匿で 404 に倒れる URL（非公開・他テナント・削除済みを区別しない公開活動記録の詳細）
const OTHER_TENANT_PATH = '/activity/987654321'

const here = dirname(fileURLToPath(import.meta.url))

type ErrorPageMessages = { not_found_title: string; back_home: string }

/** 未定義キーは空文字にする（各テストの toBeTruthy で red になる） */
function errorPageMessages(lang: Lang): ErrorPageMessages {
  const file = resolve(here, '../../../app/locales', lang, 'common.json')
  const json = JSON.parse(readFileSync(file, 'utf-8')) as Record<string, unknown>
  const ns = (json.error_page ?? {}) as Partial<ErrorPageMessages>
  return { not_found_title: ns.not_found_title ?? '', back_home: ns.back_home ?? '' }
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/** dev の Nuxt エラーオーバーレイ（id/class/タグ名に nuxt-error-overlay を含む要素）か */
const OVERLAY_MARK = 'nuxt-error-overlay'

/**
 * SSR HTML から利用者に見える本文テキストだけを取り出す。
 * __NUXT_DATA__ 等の <script>（Nuxt 標準のエラー直列化 "Page not found: /path" を含む）、
 * <style>/<template>/<noscript>/<iframe>、dev のエラーオーバーレイ要素、タグ・属性値を除く。
 */
function visibleTextOfSsrHtml(html: string): string {
  return html
    .replace(/<(script|style|template|noscript|iframe)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
    .replace(new RegExp(`<([a-z][\\w-]*)\\b[^>]*${OVERLAY_MARK}[^>]*>[\\s\\S]*?</\\1>`, 'gi'), ' ')
    .replace(new RegExp(`<${OVERLAY_MARK}\\b[\\s\\S]*?</${OVERLAY_MARK}>`, 'gi'), ' ')
    .replace(/<[^>]*>/g, ' ')
}

/** 描画済みページの body から、script 等と dev のエラーオーバーレイを除いた本文テキストを取り出す */
async function visibleBodyText(page: Page): Promise<string> {
  return page.evaluate((mark) => {
    const clone = document.body.cloneNode(true) as HTMLElement
    clone
      .querySelectorAll(`script, style, template, noscript, iframe, ${mark}, [id*="${mark}"], [class*="${mark}"]`)
      .forEach((el) => el.remove())
    return clone.textContent ?? ''
  }, OVERLAY_MARK)
}

async function setLocaleCookie(page: Page, baseURL: string | undefined, lang: Lang) {
  if (!baseURL) throw new Error('locale Cookie 試験には baseURL が必要です')
  await page.context().addCookies([
    { name: 'i18n_locale', value: lang, url: new URL('/', baseURL).href, sameSite: 'Lax' },
  ])
}

test.describe('CMP-261007-2053 error.vue の言語追従', () => {
  for (const lang of LANGS) {
    test(`AC-7 / AC-8: Cookie=${lang} で存在しない URL を直開きすると SSR から ${lang} の 404 と <html lang> が出る`, async ({ page, request, baseURL }) => {
      const msg = errorPageMessages(lang)
      expect(msg.not_found_title, `${lang} の error_page.not_found_title`).toBeTruthy()

      const res = await request.get(NOT_EXIST_PATH, { headers: { Cookie: `i18n_locale=${lang}` } })
      expect(res.status()).toBe(404)
      const html = await res.text()
      expect(html).toMatch(new RegExp(`<html[^>]*\\blang="${lang}"`))
      expect(html).toContain(escapeHtml(msg.not_found_title))
      // AC の趣旨は「利用者に見える文言に英語固定の既定文言が出ない」。
      // __NUXT_DATA__ ペイロード（Nuxt 標準のエラー直列化）と dev オーバーレイは検査対象外とする
      expect(visibleTextOfSsrHtml(html)).toContain(escapeHtml(msg.not_found_title))
      expect(visibleTextOfSsrHtml(html)).not.toMatch(/Page not found/i)

      await setLocaleCookie(page, baseURL, lang)
      await page.goto(NOT_EXIST_PATH)
      await waitForHydration(page)
      await expect(page.getByTestId('error-page-title')).toHaveText(msg.not_found_title)
      await expect(page.getByTestId('error-page-home')).toContainText(msg.back_home)
      await expect(page.getByTestId('error-page')).not.toContainText(/Page not found/i)
      expect(await visibleBodyText(page)).not.toMatch(/Page not found/i)
      expect(await page.evaluate(() => document.documentElement.lang)).toBe(lang)
    })
  }

  test('AC-7c: 不存在 URL と他テナント URL の 404 は同じ汎用文言で、内部 message/stack/資源情報を出さない', async ({ page, request, baseURL }) => {
    await setLocaleCookie(page, baseURL, 'ja')

    const ssr = await request.get(OTHER_TENANT_PATH, { headers: { Cookie: 'i18n_locale=ja' } })
    expect(ssr.status()).toBe(404)

    await page.goto(NOT_EXIST_PATH)
    await waitForHydration(page)
    const notExistText = await page.getByTestId('error-page').innerText()

    await page.goto(OTHER_TENANT_PATH)
    await waitForHydration(page)
    const otherTenantText = await page.getByTestId('error-page').innerText()

    expect(otherTenantText).toBe(notExistText)
    for (const leak of ['987654321', 'statusMessage', 'stack', ' at ', 'Error:', 'not found']) {
      expect(otherTenantText, `内部情報 ${leak} を出さない`).not.toContain(leak)
    }
  })

  test('AC-9: 「ホームへ戻る」で通常ページに戻る（エラー表示が消え、再読み込みなしで遷移）', async ({ page, baseURL }) => {
    await setLocaleCookie(page, baseURL, 'ja')
    await page.goto(NOT_EXIST_PATH)
    await waitForHydration(page)
    await page.evaluate(() => {
      ;(window as unknown as { __cmp2053NoReload?: boolean }).__cmp2053NoReload = true
    })
    await page.getByTestId('error-page-home').click()
    await expect(page.getByTestId('error-page')).toHaveCount(0)
    await expect.poll(() => new URL(page.url()).pathname).not.toBe(NOT_EXIST_PATH)
    expect(
      await page.evaluate(() => (window as unknown as { __cmp2053NoReload?: boolean }).__cmp2053NoReload),
    ).toBe(true)
  })

  test('AC-9: 未ログイン（認証ストア未初期化）で直開きしても描画が落ちない', async ({ page, baseURL }) => {
    const failures: string[] = []
    page.on('pageerror', (e) => failures.push(e.message))
    const en = errorPageMessages('en')
    expect(en.not_found_title).toBeTruthy()
    await setLocaleCookie(page, baseURL, 'en')
    await page.goto(NOT_EXIST_PATH)
    await waitForHydration(page)
    await expect(page.getByTestId('error-page-title')).toHaveText(en.not_found_title)
    expect(failures).toEqual([])
  })

  test('AC-7b: Cookie=de・アカウント言語=ja のログイン済みでは SSR は de、水和とアカウント言語適用後は ja', async ({ page, request, baseURL }) => {
    const de = errorPageMessages('de')
    const ja = errorPageMessages('ja')
    expect(de.not_found_title).toBeTruthy()
    expect(ja.not_found_title).toBeTruthy()

    // SSR は Cookie の言語（アカウント言語はクライアントの localStorage にしか無い）
    const res = await request.get(NOT_EXIST_PATH, { headers: { Cookie: 'i18n_locale=de' } })
    expect(res.status()).toBe(404)
    const html = await res.text()
    expect(html).toMatch(/<html[^>]*\blang="de"/)
    expect(html).toContain(escapeHtml(de.not_found_title))

    // ログイン済み（アカウント言語 ja）の状態を localStorage に置く
    await page.addInitScript(() => {
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 1,
          email: 'locale-e2e@example.com',
          displayName: 'ロケール検証',
          profileImageUrl: null,
          locale: 'ja',
          timezone: 'Asia/Tokyo',
        }),
      )
    })
    await setLocaleCookie(page, baseURL, 'de')
    await page.goto(NOT_EXIST_PATH)
    await waitForHydration(page)
    await expect(page.getByTestId('error-page-title')).toHaveText(ja.not_found_title, { timeout: 60_000 })
    await expect.poll(() => page.evaluate(() => document.documentElement.lang), { timeout: 60_000 }).toBe('ja')
    const cookies = (await page.context().cookies()).filter((c) => c.name === 'i18n_locale')
    expect(cookies.map((c) => c.value)).toEqual(['ja'])
  })
})
