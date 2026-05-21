import { test, expect } from '@playwright/test'
import { waitForHydration } from './helpers/wait'
import { fillInput } from './helpers/form'

/**
 * F01.9 保護者同意ページ群 E2E テスト
 *
 * 対象ページ:
 *   - /parental-consent/pending  : 子ユーザー向け招待送信・状態確認（認証必須）
 *   - /parental-consent/approve  : 保護者承認・否認（認証不要、トークン参照）
 *   - /parental-consent/manage   : 保護者/子リンク管理（認証必須）
 *
 * テスト件数: 25件（PC-001〜PC-025）
 * - 認証ガード: PC-001〜PC-003
 * - approve.vue（未認証）: PC-004〜PC-006
 * - pending.vue: PC-007〜PC-014
 * - approve.vue（認証済み）: PC-015〜PC-018
 * - manage.vue: PC-019〜PC-025
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

// ════════════════════════════════════════════════════════════
// PC-004〜PC-006: approve.vue（未認証 + トークンあり）
// ════════════════════════════════════════════════════════════

test.describe('PC-004〜006: approve ページ（未認証・有効トークン）', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('PC-004: 未認証 + 有効トークンあり → ログイン/登録ボタンが表示される', async ({
    page,
  }) => {
    // 未認証のためapprove API は呼ばれないことを確認（モックなしでアクセス）
    await page.goto('/parental-consent/approve?token=valid-token-abc')
    await waitForHydration(page)

    // ログイン必須メッセージが表示されること
    await expect(
      page.getByText('承認するにはMannschaftアカウントでログインが必要です'),
    ).toBeVisible({ timeout: 10_000 })

    // ログインボタンが表示されること
    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible({
      timeout: 5_000,
    })

    // アカウントを作成ボタンが表示されること
    await expect(page.getByRole('button', { name: 'アカウントを作成' })).toBeVisible({
      timeout: 5_000,
    })
  })

  test('PC-005: 未認証 + 有効トークン → ログインボタンクリック → /login へ遷移する', async ({
    page,
  }) => {
    await page.goto('/parental-consent/approve?token=valid-token-abc')
    await waitForHydration(page)

    // ログインボタンが表示されるのを待つ
    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible({
      timeout: 10_000,
    })

    // ログインボタンをクリック
    await page.getByRole('button', { name: 'ログイン' }).click()

    // /login を含む URL に遷移すること
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
  })

  test('PC-006: 未認証 + 有効トークン → アカウントを作成ボタンクリック → /register へ遷移する', async ({
    page,
  }) => {
    await page.goto('/parental-consent/approve?token=valid-token-abc')
    await waitForHydration(page)

    // アカウントを作成ボタンが表示されるのを待つ
    await expect(page.getByRole('button', { name: 'アカウントを作成' })).toBeVisible({
      timeout: 10_000,
    })

    // アカウントを作成ボタンをクリック
    await page.getByRole('button', { name: 'アカウントを作成' }).click()

    // /register を含む URL に遷移すること
    await expect(page).toHaveURL(/\/register/, { timeout: 10_000 })
  })
})

// ════════════════════════════════════════════════════════════
// PC-007〜PC-014: pending.vue テスト群（認証済み）
// ════════════════════════════════════════════════════════════

test.describe('PC-007〜014: pending.vue（認証済み）', () => {
  // デフォルト storageState（認証済み）を使用

  test('PC-007: 招待なし・保護者なし → フォームと空メッセージが表示される', async ({
    page,
  }) => {
    // API モックを設定
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    // ページタイトルが表示されること
    await expect(
      page.getByRole('heading', { name: '保護者の同意が必要です' }),
    ).toBeVisible({ timeout: 10_000 })

    // 空メッセージが表示されること
    await expect(
      page.getByText('まだ招待を送信していません'),
    ).toBeVisible({ timeout: 8_000 })

    // メールアドレス入力フィールドが表示されること
    await expect(
      page.locator('input[placeholder="parent@example.com"]'),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PC-008: 招待送信成功 → 成功メッセージ表示', async ({ page }) => {
    let getCount = 0

    // 招待一覧 API: 初回は空、POST 後の再取得で 1件返す
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        getCount++
        if (getCount === 1) {
          // 初回取得: 空
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: [] }),
          })
        } else {
          // POST 後の再取得: 1件
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: [
                {
                  linkId: 'inv-1',
                  parentEmail: 'parent@test.com',
                  status: 'PENDING',
                  expiresAt: '2026-12-31T00:00:00Z',
                  createdAt: '2026-05-21T00:00:00Z',
                },
              ],
            }),
          })
        }
      } else if (method === 'POST') {
        // 招待送信成功
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: { linkId: 'inv-1' } }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    // フォームが表示されるまで待つ
    const emailInput = page.locator('input[placeholder="parent@example.com"]')
    await expect(emailInput).toBeVisible({ timeout: 10_000 })

    // メールアドレスを入力
    await fillInput(emailInput, 'parent@test.com')

    // 招待を送信ボタンをクリック
    const sendBtn = page.getByRole('button', { name: '招待を送信' })
    await expect(sendBtn).toBeVisible({ timeout: 5_000 })
    await sendBtn.click()

    // 成功メッセージが表示されること
    await expect(
      page.getByText('招待メールを送信しました'),
    ).toBeVisible({ timeout: 8_000 })
  })

  test('PC-009: PENDING 招待がある場合 → 一覧に承認待ちラベルと取消ボタンが表示される', async ({
    page,
  }) => {
    // PENDING 招待 1件を返すモック
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                linkId: 'inv-1',
                parentEmail: 'parent@test.com',
                status: 'PENDING',
                expiresAt: '2026-12-31T00:00:00Z',
                createdAt: '2026-05-21T00:00:00Z',
              },
            ],
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    // 招待先メールアドレスが表示されること
    await expect(page.getByText('parent@test.com')).toBeVisible({ timeout: 10_000 })

    // 承認待ちラベルが表示されること
    await expect(page.getByText('承認待ち')).toBeVisible({ timeout: 5_000 })

    // 招待を取消ボタンが表示されること
    await expect(page.getByRole('button', { name: '招待を取消' })).toBeVisible({
      timeout: 5_000,
    })
  })

  test('PC-010: PENDING 招待の取消し → 成功メッセージ表示', async ({ page }) => {
    let getCount = 0

    // 招待一覧: 初回は PENDING × 1、DELETE 後は空
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        getCount++
        if (getCount === 1) {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: [
                {
                  linkId: 'inv-1',
                  parentEmail: 'parent@test.com',
                  status: 'PENDING',
                  expiresAt: '2026-12-31T00:00:00Z',
                  createdAt: '2026-05-21T00:00:00Z',
                },
              ],
            }),
          })
        } else {
          // DELETE 後の再取得: 空
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: [] }),
          })
        }
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    // 招待取消 API モック
    await page.route('**/api/v1/parental-consent/invitations/inv-1', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204, body: '' })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    // 招待一覧が表示されるまで待つ
    await expect(page.getByRole('button', { name: '招待を取消' })).toBeVisible({
      timeout: 10_000,
    })

    // 招待を取消ボタンをクリック
    await page.getByRole('button', { name: '招待を取消' }).click()

    // 成功メッセージが表示されること
    await expect(page.getByText('招待を取消しました')).toBeVisible({ timeout: 8_000 })
  })

  test('PC-011: 承認済み保護者がいる場合 → /parental-consent/pending 以外の URL にリダイレクト', async ({
    page,
  }) => {
    // 承認済み保護者を返すモック
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                linkId: 'link-1',
                parentEmail: 'parent@example.com',
                parentUserId: 99,
                approvedAt: '2026-05-01T00:00:00Z',
              },
            ],
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    // リダイレクトが発生するため URL が /parental-consent/pending でなくなることを確認
    await expect(page).not.toHaveURL(/\/parental-consent\/pending/, { timeout: 10_000 })
  })

  test('PC-012: 招待上限エラー (AUTH_067) → エラー通知表示', async ({ page }) => {
    // 招待上限エラーを返すモック
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else if (method === 'POST') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: '招待は最大3件まで', code: 'AUTH_067' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    // フォームが表示されるまで待つ
    const emailInput = page.locator('input[placeholder="parent@example.com"]')
    await expect(emailInput).toBeVisible({ timeout: 10_000 })

    // メールアドレスを入力して送信
    await fillInput(emailInput, 'parent@test.com')
    await page.getByRole('button', { name: '招待を送信' }).click()

    // AUTH_067 エラーメッセージが表示されること
    await expect(
      page.getByText('招待は最大3件まで同時に送信できます'),
    ).toBeVisible({ timeout: 8_000 })
  })

  test('PC-013: 重複招待エラー (AUTH_068) → エラー通知表示', async ({ page }) => {
    // 重複招待エラーを返すモック
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else if (method === 'POST') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: '既に送信済み', code: 'AUTH_068' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    const emailInput = page.locator('input[placeholder="parent@example.com"]')
    await expect(emailInput).toBeVisible({ timeout: 10_000 })

    await fillInput(emailInput, 'already@test.com')
    await page.getByRole('button', { name: '招待を送信' }).click()

    // AUTH_068 エラーメッセージが表示されること
    await expect(
      page.getByText('このメールアドレスへの招待は既に送信されています'),
    ).toBeVisible({ timeout: 8_000 })
  })

  test('PC-014: 自己招待エラー (AUTH_069) → エラー通知表示', async ({ page }) => {
    // 自己招待エラーを返すモック
    await page.route('**/api/v1/parental-consent/invitations', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else if (method === 'POST') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: '自己招待不可', code: 'AUTH_069' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/pending')
    await waitForHydration(page)

    const emailInput = page.locator('input[placeholder="parent@example.com"]')
    await expect(emailInput).toBeVisible({ timeout: 10_000 })

    await fillInput(emailInput, 'myself@test.com')
    await page.getByRole('button', { name: '招待を送信' }).click()

    // AUTH_069 エラーメッセージが表示されること
    await expect(
      page.getByText('自分のメールアドレスへの招待はできません'),
    ).toBeVisible({ timeout: 8_000 })
  })
})

// ════════════════════════════════════════════════════════════
// PC-015〜PC-018: approve.vue テスト群（認証済み）
// ════════════════════════════════════════════════════════════

test.describe('PC-015〜018: approve.vue（認証済み）', () => {
  // デフォルト storageState（認証済み）を使用

  test('PC-015: 認証済み + 有効トークン → 自動承認成功 → 承認済みメッセージ表示', async ({
    page,
  }) => {
    // 承認 API 成功モック
    await page.route('**/api/v1/parental-consent/approve', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: {} }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/approve?token=valid-token-abc')
    await waitForHydration(page)

    // 自動承認成功後に承認済みメッセージが表示されること
    await expect(
      page.getByText('保護者同意を承認しました'),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('PC-016: 認証済み + 無効トークン(AUTH_060) → invalid_token メッセージ表示', async ({
    page,
  }) => {
    // 承認 API が AUTH_060 エラーを返すモック
    await page.route('**/api/v1/parental-consent/approve', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: '無効なトークン', code: 'AUTH_060' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/approve?token=expired-token')
    await waitForHydration(page)

    // エラーメッセージが表示されること
    await expect(
      page.getByText('リンクが無効または期限切れです'),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('PC-017: 認証済み + AUTH_062（自己承認不可）→ 承認/否認ボタンが表示される', async ({
    page,
  }) => {
    // 自動承認が AUTH_062 で失敗 → error.value は false のまま → ボタン表示
    await page.route('**/api/v1/parental-consent/approve', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ message: '自己承認不可', code: 'AUTH_062' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/approve?token=valid-token-abc')
    await waitForHydration(page)

    // ローディングが終わるまで待つ
    // AUTH_062 の場合は error.value = false のまま → v-else ブロックに入る → ボタンが表示される
    await expect(
      page.getByRole('button', { name: '許可する' }),
    ).toBeVisible({ timeout: 10_000 })

    // 否認ボタンも表示されること
    await expect(
      page.getByRole('button', { name: '許可しない' }),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PC-018: 認証済み + AUTH_062 後 → 許可しないボタンクリック → 確認 → 否認実行 → 否認済みメッセージ', async ({
    page,
  }) => {
    // 承認 API: AUTH_062 エラー（自動承認失敗でボタンを露出させる）
    await page.route('**/api/v1/parental-consent/approve', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ message: '自己承認不可', code: 'AUTH_062' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    // 否認 API: 成功
    await page.route('**/api/v1/parental-consent/reject', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: {} }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/approve?token=valid-token-abc')
    await waitForHydration(page)

    // AUTH_062 後にボタンが表示されるまで待つ
    const rejectBtn = page.getByRole('button', { name: '許可しない' }).first()
    await expect(rejectBtn).toBeVisible({ timeout: 10_000 })

    // 「許可しない」ボタンをクリック → showRejectConfirm = true
    await rejectBtn.click()

    // 確認メッセージが表示されること
    await expect(
      page.getByText('同意を拒否すると、アカウントが削除される場合があります。よろしいですか？'),
    ).toBeVisible({ timeout: 5_000 })

    // 確認ダイアログ内の「許可しない」ボタンをクリック（否認実行）
    // 確認ダイアログ内のボタンは 2番目の「許可しない」
    const rejectBtns = page.getByRole('button', { name: '許可しない' })
    await rejectBtns.last().click()

    // 否認済みメッセージが表示されること
    await expect(
      page.getByText('保護者同意を否認しました'),
    ).toBeVisible({ timeout: 8_000 })
  })
})

// ════════════════════════════════════════════════════════════
// PC-019〜PC-025: manage.vue テスト群（認証済み）
// ════════════════════════════════════════════════════════════

test.describe('PC-019〜025: manage.vue（認証済み）', () => {
  // デフォルト storageState（認証済み）を使用

  test('PC-019: 保護者リスト表示 → 保護者情報と解除ボタンが表示される', async ({ page }) => {
    // 保護者 1件を返すモック
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                linkId: 'link-1',
                parentEmail: 'guardian@example.com',
                parentUserId: 99,
                approvedAt: '2026-05-01T00:00:00Z',
              },
            ],
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // ページタイトルが表示されること
    await expect(
      page.getByRole('heading', { name: '保護者管理' }),
    ).toBeVisible({ timeout: 10_000 })

    // 保護者セクションのタイトルが表示されること
    await expect(
      page.getByRole('heading', { name: '承認済みの保護者' }),
    ).toBeVisible({ timeout: 5_000 })

    // 保護者のメールアドレスが表示されること
    await expect(page.getByText('guardian@example.com')).toBeVisible({ timeout: 5_000 })

    // 保護者リンクを解除ボタンが表示されること
    await expect(
      page.getByRole('button', { name: '保護者リンクを解除' }),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PC-020: 子リスト表示 → 子の表示名とリンク解除ボタンが表示される', async ({ page }) => {
    // 子 1件を返すモック
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                linkId: 'link-2',
                childUserId: 42,
                childDisplayName: 'テストの子',
                approvedAt: '2026-05-01T00:00:00Z',
              },
            ],
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // 子セクションのタイトルが表示されること
    await expect(
      page.getByRole('heading', { name: '監護している子アカウント' }),
    ).toBeVisible({ timeout: 10_000 })

    // 子の表示名が表示されること
    await expect(page.getByText('テストの子')).toBeVisible({ timeout: 5_000 })

    // リンクを解除ボタンが表示されること
    await expect(
      page.getByRole('button', { name: 'リンクを解除' }),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PC-021: 空状態 → 保護者なし・子なし メッセージが表示される', async ({ page }) => {
    // 空データを返すモック
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // 保護者なしメッセージが表示されること
    await expect(
      page.getByText('承認された保護者がいません'),
    ).toBeVisible({ timeout: 10_000 })

    // 子なしメッセージが表示されること
    await expect(
      page.getByText('監護している子アカウントがありません'),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PC-022: 保護者リンク解除成功 → 成功メッセージ表示', async ({ page }) => {
    let getParentsCount = 0

    // 保護者: 初回は 1件、DELETE 後は空
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        getParentsCount++
        if (getParentsCount === 1) {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: [
                {
                  linkId: 'link-1',
                  parentEmail: 'guardian@example.com',
                  parentUserId: 99,
                  approvedAt: '2026-05-01T00:00:00Z',
                },
              ],
            }),
          })
        } else {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: [] }),
          })
        }
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    // 保護者リンク解除 API モック
    await page.route('**/api/v1/parental-consent/parents/link-1', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204, body: '' })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // 保護者リンクを解除ボタンが表示されるまで待つ
    await expect(
      page.getByRole('button', { name: '保護者リンクを解除' }),
    ).toBeVisible({ timeout: 10_000 })

    // 解除ボタンをクリック
    await page.getByRole('button', { name: '保護者リンクを解除' }).click()

    // 成功メッセージが表示されること
    await expect(page.getByText('リンクを解除しました')).toBeVisible({ timeout: 8_000 })
  })

  test('PC-023: 最後の保護者削除不可 (AUTH_064) → エラーメッセージ表示', async ({ page }) => {
    // 保護者 1件を返すモック
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                linkId: 'link-1',
                parentEmail: 'guardian@example.com',
                parentUserId: 99,
                approvedAt: '2026-05-01T00:00:00Z',
              },
            ],
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    // 削除 API が AUTH_064 エラーを返すモック
    await page.route('**/api/v1/parental-consent/parents/link-1', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ message: '最後の保護者リンク', code: 'AUTH_064' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // 解除ボタンが表示されるまで待つ
    await expect(
      page.getByRole('button', { name: '保護者リンクを解除' }),
    ).toBeVisible({ timeout: 10_000 })

    // 解除ボタンをクリック
    await page.getByRole('button', { name: '保護者リンクを解除' }).click()

    // AUTH_064 エラーメッセージが表示されること
    await expect(
      page.getByText('最後の保護者リンクは削除できません'),
    ).toBeVisible({ timeout: 8_000 })
  })

  test('PC-024: 子リンク解除成功 → 成功メッセージ表示', async ({ page }) => {
    let getChildrenCount = 0

    // 子: 初回は 1件、DELETE 後は空
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        getChildrenCount++
        if (getChildrenCount === 1) {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: [
                {
                  linkId: 'link-2',
                  childUserId: 42,
                  childDisplayName: 'テストの子',
                  approvedAt: '2026-05-01T00:00:00Z',
                },
              ],
            }),
          })
        } else {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: [] }),
          })
        }
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    // 子リンク解除 API モック
    await page.route('**/api/v1/parental-consent/children/link-2', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204, body: '' })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // リンクを解除ボタンが表示されるまで待つ
    await expect(
      page.getByRole('button', { name: 'リンクを解除' }),
    ).toBeVisible({ timeout: 10_000 })

    // 解除ボタンをクリック
    await page.getByRole('button', { name: 'リンクを解除' }).click()

    // 成功メッセージが表示されること
    await expect(page.getByText('リンクを解除しました')).toBeVisible({ timeout: 8_000 })
  })

  test('PC-025: 最後の子リンク解除不可 (AUTH_065) → エラーメッセージ表示', async ({ page }) => {
    // 子 1件を返すモック
    await page.route('**/api/v1/parental-consent/parents', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    await page.route('**/api/v1/parental-consent/children', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                linkId: 'link-2',
                childUserId: 42,
                childDisplayName: 'テストの子',
                approvedAt: '2026-05-01T00:00:00Z',
              },
            ],
          }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })
    // 削除 API が AUTH_065 エラーを返すモック
    await page.route('**/api/v1/parental-consent/children/link-2', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ message: '最後の子リンク', code: 'AUTH_065' }),
        })
      } else {
        await route.fulfill({ status: 404, body: '' })
      }
    })

    await page.goto('/parental-consent/manage')
    await waitForHydration(page)

    // リンクを解除ボタンが表示されるまで待つ
    await expect(
      page.getByRole('button', { name: 'リンクを解除' }),
    ).toBeVisible({ timeout: 10_000 })

    // 解除ボタンをクリック
    await page.getByRole('button', { name: 'リンクを解除' }).click()

    // AUTH_065 エラーメッセージが表示されること
    await expect(
      page.getByText('最後の保護者リンクは解除できません'),
    ).toBeVisible({ timeout: 8_000 })
  })
})
