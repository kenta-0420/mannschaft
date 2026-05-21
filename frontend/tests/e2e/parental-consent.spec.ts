import { test, expect } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F01.9 保護者同意ページ群 E2E テスト
 *
 * 対象ページ:
 *   - /parental-consent/pending  : 子ユーザー向け招待送信・状態確認（認証必須）
 *   - /parental-consent/approve  : 保護者承認・否認（認証不要、トークン参照）
 *   - /parental-consent/manage   : 保護者/子リンク管理（認証必須）
 *
 * 認証が必要なページは未認証状態（storageState 空）でテストし、
 * ログインページへリダイレクトされることを確認する。
 *
 * バックエンド API モックは page.route で実施（BE 依存なし）。
 */

// ════════════════════════════════════════════════════════════
// PC-001: 未認証で /parental-consent/pending へアクセス
// ════════════════════════════════════════════════════════════

test.describe('PC-001: 未認証アクセスのリダイレクト確認（pending）', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('PC-001: 未認証で /parental-consent/pending にアクセスするとログインへリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/parental-consent/pending')
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
  })
})

// ════════════════════════════════════════════════════════════
// PC-002: 未認証で /parental-consent/manage へアクセス
// ════════════════════════════════════════════════════════════

test.describe('PC-002: 未認証アクセスのリダイレクト確認（manage）', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('PC-002: 未認証で /parental-consent/manage にアクセスするとログインへリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/parental-consent/manage')
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
  })
})

// ════════════════════════════════════════════════════════════
// PC-003: トークンなしで /parental-consent/approve へアクセス
// ════════════════════════════════════════════════════════════

test.describe('PC-003: approve ページ（トークンなし）', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('PC-003: トークンなしで /parental-consent/approve にアクセスすると invalid_token メッセージが表示される', async ({
    page,
  }) => {
    // トークン未指定（クエリパラメータなし）でアクセス
    await page.goto('/parental-consent/approve')
    await waitForHydration(page)

    // invalid_token メッセージが表示されること
    await expect(
      page.getByText('リンクが無効または期限切れです'),
    ).toBeVisible({ timeout: 10_000 })
  })
})
