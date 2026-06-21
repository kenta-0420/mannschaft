// SET-DEEP-001〜005: プロフィール設定フォームの深掘りテスト
// 表示名・電話番号の入力 / 保存 API 呼び出し / アバターアップロードボタンの操作性を検証する

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, clearAndFillInput } from '../helpers/form'

const MOCK_PROFILE = {
  data: {
    id: 1,
    displayName: 'テストユーザー',
    nickname: 'テストユーザー',  // profile.vue の v-model="profile.nickname" に対応
    email: 'test@example.com',
    phoneNumber: '090-1234-5678',
    avatarUrl: null,
    hasPassword: true,
  },
}

const MOCK_HANDLE = {
  data: { contactHandle: null },
}

/**
 * profile.vue は onMounted で /api/v1/users/me（GET）と
 * /api/v1/users/me/contact-handle（GET）の 2 本を呼ぶ。
 * テスト中は両方をモックし、保存ボタンクリック時の PUT を観測する。
 * ※ 表示名保存は PUT /api/v1/users/me（BE: @PutMapping("/me")）。PATCH は 405 になる。
 */
async function setupProfileMocks(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/users/me/contact-handle', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_HANDLE),
    })
  })
  await page.route('**/api/v1/users/me', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    } else {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    }
  })
}

test.describe('SET-DEEP profile: プロフィール設定フォーム深掘り', () => {
  test('SET-DEEP-001: 表示名を変更して保存ボタンを押すと PUT /api/v1/users/me が呼ばれる', async ({
    page,
  }) => {
    await setupProfileMocks(page)

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    // 初期値が読み込まれた表示名インプットを取得して書き換える
    const displayNameInput = page
      .locator('label')
      .filter({ hasText: '表示名' })
      .locator('xpath=following-sibling::input')
      .first()
    await expect(displayNameInput).toHaveValue('テストユーザー', { timeout: 10_000 })

    await clearAndFillInput(displayNameInput, '更新後ユーザー')

    // 保存ボタンクリック → PUT リクエストを観測（PATCHではなくPUT: BE は @PutMapping("/me")）
    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/users/me') &&
        !req.url().includes('contact-handle') &&
        req.method() === 'PUT',
      { timeout: 10_000 },
    )

    await page.getByRole('button', { name: '保存' }).first().click()
    const putReq = await putPromise

    const body = JSON.parse(putReq.postData() ?? '{}')
    expect(body.nickname).toBe('更新後ユーザー')

    // 成功通知が表示される
    await expect(page.getByText('プロフィールを更新しました')).toBeVisible({ timeout: 10_000 })
  })

  test('SET-DEEP-002: 電話番号を別形式に書き換えて保存すると PUT ボディに反映される', async ({
    page,
  }) => {
    await setupProfileMocks(page)

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    const phoneInput = page.locator('input[placeholder="090-0000-0000"]')
    await expect(phoneInput).toHaveValue('090-1234-5678', { timeout: 10_000 })

    await clearAndFillInput(phoneInput, '080-9999-0000')

    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/users/me') &&
        !req.url().includes('contact-handle') &&
        req.method() === 'PUT',
      { timeout: 10_000 },
    )
    await page.getByRole('button', { name: '保存' }).first().click()
    const putReq = await putPromise

    const body = JSON.parse(putReq.postData() ?? '{}')
    expect(body.phoneNumber).toBe('080-9999-0000')
  })

  test('SET-DEEP-003: 保存 API がエラーを返してもエラー通知が出て入力値は保持される', async ({
    page,
  }) => {
    // GET と PATCH を分岐させて PATCH のみ 500 を返す
    await page.route('**/api/v1/users/me/contact-handle', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE),
      })
    })
    await page.route('**/api/v1/users/me', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_PROFILE),
        })
      } else {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: { message: 'Internal Server Error' } }),
        })
      }
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    const displayNameInput = page
      .locator('label')
      .filter({ hasText: '表示名' })
      .locator('xpath=following-sibling::input')
      .first()
    await expect(displayNameInput).toHaveValue('テストユーザー', { timeout: 10_000 })

    await clearAndFillInput(displayNameInput, 'エラー検証ユーザー')
    await page.getByRole('button', { name: '保存' }).first().click()

    // エラー通知が出る
    await expect(page.getByText('プロフィールの更新に失敗しました')).toBeVisible({
      timeout: 10_000,
    })
    // 入力値は保持されたままになる
    await expect(displayNameInput).toHaveValue('エラー検証ユーザー')
  })

  test('SET-DEEP-004: メールアドレス入力欄は disabled で編集不可', async ({ page }) => {
    await setupProfileMocks(page)

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    // メールアドレス欄は disabled 属性付きで編集できない
    const emailInput = page
      .locator('label')
      .filter({ hasText: 'メールアドレス' })
      .locator('xpath=following-sibling::input')
      .first()
    await expect(emailInput).toHaveValue('test@example.com', { timeout: 10_000 })
    await expect(emailInput).toBeDisabled()
    // サポート問い合わせ案内文も表示される
    await expect(
      page.getByText('メールアドレスの変更はサポートにお問い合わせください'),
    ).toBeVisible()
  })

  test('SET-DEEP-005: アバター変更ボタンと隠し file input が DOM に存在する', async ({
    page,
  }) => {
    await setupProfileMocks(page)

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    // 「画像を変更」ボタンが見えていること
    await expect(page.getByText('画像を変更')).toBeVisible({ timeout: 5_000 })
    // 5MB 以下の案内文も表示されている
    await expect(page.getByText('5MB以下のJPG, PNG')).toBeVisible()
    // 内部の input[type=file] は hidden だが DOM 上には存在する
    const fileInput = page.locator('input[type="file"][accept="image/*"]')
    await expect(fileInput).toHaveCount(1)

    // 表示名を読み込んだ状態で fillInput が動くことだけ最後に確認する
    const displayNameInput = page
      .locator('label')
      .filter({ hasText: '表示名' })
      .locator('xpath=following-sibling::input')
      .first()
    await fillInput(displayNameInput, '_追記')
    await expect(displayNameInput).toHaveValue('テストユーザー_追記')
  })

  // ──────────────────────────────────────────────────────────────────────────
  // SET-DEEP-006〜007: API 契約回帰テスト（実機E2Eで確定した 405/500 バグの再発防止）
  // - 表示名保存は PATCH ではなく PUT を使う（BE: @PutMapping("/me")）
  // - アバターは廃止済みの /users/me/avatar を叩かず presigned 方式の /profile-media/icon/upload-url を叩く
  // ──────────────────────────────────────────────────────────────────────────

  test('SET-DEEP-006: 表示名保存は PUT /api/v1/users/me を発火し PATCH は発火しない', async ({
    page,
  }) => {
    await setupProfileMocks(page)

    // PATCH が届いたらテストを失敗させるセンチネル
    const patchRequests: string[] = []
    await page.route('**/api/v1/users/me', async (route) => {
      if (route.request().method() === 'PATCH') {
        patchRequests.push(route.request().url())
        await route.fulfill({
          status: 405,
          contentType: 'application/json',
          body: JSON.stringify({ error: { message: 'Method Not Allowed' } }),
        })
      } else if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_PROFILE),
        })
      } else {
        // GET
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_PROFILE),
        })
      }
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    // PUT リクエストの発火を観測する
    const putPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/users/me') &&
        !req.url().includes('contact-handle') &&
        req.method() === 'PUT',
      { timeout: 10_000 },
    )

    await page.getByRole('button', { name: '保存' }).first().click()
    await putPromise

    // PATCH は一度も発火していないことを確認
    expect(patchRequests).toHaveLength(0)

    await expect(page.getByText('プロフィールを更新しました')).toBeVisible({ timeout: 10_000 })
  })

  test('SET-DEEP-007: アバター選択時に /profile-media/icon/upload-url へ向かい 旧EP /users/me/avatar は叩かない', async ({
    page,
  }) => {
    await setupProfileMocks(page)

    // 旧EP への POST が届いたらセンチネルに記録
    const legacyAvatarRequests: string[] = []
    await page.route('**/api/v1/users/me/avatar', async (route) => {
      legacyAvatarRequests.push(route.request().url())
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: { message: 'Not Found' } }),
      })
    })

    // presigned upload-url EP をモック
    await page.route('**/profile-media/icon/upload-url', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            uploadUrl: 'https://mock-r2.example.com/upload',
            r2Key: 'users/mock-user-id/icon/test.jpg',
          },
        }),
      })
    })

    // R2 直接 PUT をモック（fetch は page.route で傍受可能）
    await page.route('https://mock-r2.example.com/upload', async (route) => {
      await route.fulfill({ status: 200 })
    })

    // コミット EP をモック
    await page.route('**/profile-media/icon', async (route) => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              mediaRole: 'icon',
              url: 'https://cdn.example.com/users/mock-user-id/icon/test.jpg',
            },
          }),
        })
      } else {
        await route.fallback()
      }
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })

    // upload-url EP が呼ばれることを観測するリスナーを設定
    const uploadUrlPromise = page.waitForRequest(
      (req) => req.url().includes('profile-media/icon/upload-url'),
      { timeout: 10_000 },
    )

    // ダミー画像ファイルを file input に注入
    const fileInput = page.locator('input[type="file"][accept="image/*"]')
    await fileInput.setInputFiles({
      name: 'avatar.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.from('fake-image-content'),
    })

    await uploadUrlPromise

    // 旧 EP への POST は一切発火していないことを確認（契約回帰）
    expect(legacyAvatarRequests).toHaveLength(0)
  })
})
