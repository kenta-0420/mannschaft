import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createJaLocaleT } from '../helpers/localeJson'

/**
 * テスト環境（jsdom）は navigator.language が既定で 'en-US' になり、@nuxtjs/i18n の
 * detectBrowserLanguage がこれを拾って defaultLocale('ja') より優先してしまう
 * （本番はブラウザの Accept-Language/Cookie を見る正しい挙動なのでバグではないが、
 * ユニットテストでは日本語ラベルの一致を検証したいので `t` を固定する）。
 *
 * `t` は手書き辞書ではなく `app/locales/ja/common.json` を実際に読んで解決する
 * （キーが locale.json から消えたら throw で赤化させ、モックがコードを追認しないようにする）。
 */
const jaT = createJaLocaleT(['common'])
mockNuxtImport('useI18n', () => () => ({ t: jaT }))

/**
 * 欠陥3（実機E2E 2026-09-29）: システム管理のメニューから価格改定一覧 /system-admin/price-revisions へ
 * たどる導線が無かった（URL を知っている人しか到達できない）。
 *
 * - /system-admin トップの管理メニュー（SystemAdminQuickLinks）に価格改定へのリンクがある
 * - /system-admin/billing（課金マスタ管理）からも価格改定へのリンクがあり、人数バンド編集ダイアログの
 *   「移設しました」案内にも移設先へのリンクが付いている
 */

const SystemAdminQuickLinks = (await import('~/components/system-admin/SystemAdminQuickLinks.vue')).default

describe('システム管理メニュー → 価格改定の導線', () => {
  it('管理メニューに /system-admin/price-revisions へのリンクがあり、ラベルは「価格改定」', async () => {
    const wrapper = await mountSuspended(SystemAdminQuickLinks)

    const link = wrapper.find('a[href="/system-admin/price-revisions"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('価格改定')
  }, 30000)

  it('課金マスタ管理にも価格改定へのリンクがあり、人数バンドの移設案内にもリンクが付いている', () => {
    const source = readFileSync(resolve(process.cwd(), 'app/pages/system-admin/billing.vue'), 'utf8')

    expect(source).toMatch(/data-testid="billing-to-price-revisions"/)
    expect(source).toMatch(/data-testid="bands-dialog-to-price-revisions"/)
    const links = source.match(/<NuxtLink[\s\S]*?to="\/system-admin\/price-revisions"/g) ?? []
    expect(links.length).toBeGreaterThanOrEqual(2)
  })
})
