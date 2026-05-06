import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// FOLDER テストケース用モックデータ
const MY_TEAMS = [
  {
    id: 1,
    name: 'テストチームA',
    nickname1: null,
    iconUrl: null,
    role: 'ADMIN',
    template: 'SPORTS',
    memberCount: 5,
  },
  {
    id: 2,
    name: 'テストチームB',
    nickname1: 'チームB',
    iconUrl: null,
    role: 'MEMBER',
    template: 'GENERAL',
    memberCount: 3,
  },
]

const FOLDER1 = {
  id: 1,
  name: 'お気に入り',
  color: '#3B82F6',
  sortOrder: 0,
  itemScopeIds: [],
}

const FOLDER1_WITH_TEAM1 = { ...FOLDER1, itemScopeIds: [1] }

/** スコープフォルダテスト用の基本モックを設定する */
async function setupFolderBaseMocks(
  page: import('@playwright/test').Page,
  options: {
    folders?: typeof FOLDER1[]
  } = {},
) {
  const { folders = [] } = options

  await page.route('**/api/v1/me/teams', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MY_TEAMS }),
    })
  })
  await page.route('**/api/v1/me/teams/*/announcements', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/me/scope-folders**', async (route) => {
    // POST は除外し、GETのみ一覧を返す
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: folders }),
      })
    }
    else {
      await route.continue()
    }
  })
}

test.describe('FOLDER-001〜006: スコープフォルダ', () => {
  // FOLDER-001: フォルダが0件のとき「フォルダを追加」ボタンが表示される
  test('FOLDER-001: フォルダが0件のとき「フォルダを追加」ボタンが表示される', async ({
    page,
  }) => {
    await setupFolderBaseMocks(page, { folders: [] })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'フォルダを追加' })).toBeVisible({
      timeout: 5_000,
    })
  })

  // FOLDER-002: 「フォルダを追加」を押すとダイアログが開き、名前を入力して保存できる
  test('FOLDER-002: 「フォルダを追加」を押すとダイアログが開き、名前を入力して保存できる', async ({
    page,
  }) => {
    await setupFolderBaseMocks(page, { folders: [] })

    // フォルダ作成APIモック
    await page.route('**/api/v1/me/scope-folders', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: FOLDER1 }),
        })
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })

    // 「フォルダを追加」ボタンをクリック
    await page.getByRole('button', { name: 'フォルダを追加' }).click()

    // ダイアログが表示されることを確認
    await expect(
      page.getByRole('dialog'),
    ).toBeVisible({ timeout: 5_000 })

    // フォルダ名を入力
    const nameInput = page.getByRole('dialog').locator('input[type="text"]').first()
    await nameInput.fill('お気に入り')

    // 保存ボタンをクリック
    await page.getByRole('dialog').getByRole('button', { name: '保存' }).click()

    // ダイアログが閉じることを確認
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })

  // FOLDER-003: 作成したフォルダが一覧に表示される
  test('FOLDER-003: 作成したフォルダが一覧に表示される', async ({ page }) => {
    await setupFolderBaseMocks(page, { folders: [FOLDER1] })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('お気に入り')).toBeVisible({ timeout: 5_000 })
  })

  // FOLDER-004: チームの「フォルダへ移動」でフォルダを選択するとそのフォルダに移動する
  test('FOLDER-004: チームの「フォルダへ移動」でフォルダを選択するとそのフォルダに移動する', async ({
    page,
  }) => {
    await setupFolderBaseMocks(page, { folders: [FOLDER1] })

    // フォルダへアイテム追加APIモック
    await page.route('**/api/v1/me/scope-folders/1/items', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: FOLDER1_WITH_TEAM1 }),
        })
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })

    // 「フォルダへ移動」ボタンをクリック（aria-labelで特定）
    const moveButton = page.getByRole('button', { name: 'フォルダへ移動' }).first()
    await expect(moveButton).toBeVisible({ timeout: 5_000 })
    await moveButton.click()

    // ポップアップ内でフォルダ名をクリック
    await expect(page.getByText('お気に入り')).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: 'お気に入り' }).first().click()
  })

  // FOLDER-005: フォルダの「削除」ボタンで確認ダイアログが表示され、削除できる
  test('FOLDER-005: フォルダの「削除」ボタンで確認ダイアログが表示され、削除できる', async ({
    page,
  }) => {
    await setupFolderBaseMocks(page, { folders: [FOLDER1] })

    // フォルダ削除APIモック
    await page.route('**/api/v1/me/scope-folders/1', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 204,
          contentType: 'application/json',
          body: '',
        })
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('お気に入り')).toBeVisible({ timeout: 5_000 })

    // 「フォルダを削除」ボタンをクリック（aria-labelで特定）
    const deleteButton = page.getByRole('button', { name: 'フォルダを削除' }).first()
    await expect(deleteButton).toBeVisible({ timeout: 5_000 })
    await deleteButton.click()

    // 確認ダイアログが表示されることを確認
    await expect(
      page.getByRole('dialog'),
    ).toBeVisible({ timeout: 5_000 })

    // ダイアログで「はい」または削除確認ボタンをクリック
    const confirmButton = page
      .getByRole('dialog')
      .getByRole('button', { name: /はい|削除|Yes/ })
      .first()
    await expect(confirmButton).toBeVisible({ timeout: 5_000 })
    await confirmButton.click()
  })

  // FOLDER-006: チームを「フォルダから外す」と未分類に戻る
  test('FOLDER-006: チームを「フォルダから外す」と未分類に戻る', async ({ page }) => {
    // チーム1がフォルダに属している状態からスタート
    await page.route('**/api/v1/me/teams', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MY_TEAMS }),
      })
    })
    await page.route('**/api/v1/me/teams/*/announcements', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/me/scope-folders**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [FOLDER1_WITH_TEAM1] }),
        })
      }
      else {
        await route.continue()
      }
    })

    // フォルダからアイテム削除APIモック
    await page.route('**/api/v1/me/scope-folders/1/items/1', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 204,
          contentType: 'application/json',
          body: '',
        })
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/teams')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('お気に入り')).toBeVisible({ timeout: 5_000 })

    // 「フォルダへ移動」ボタン（チーム1）をクリック
    const moveButton = page.getByRole('button', { name: 'フォルダへ移動' }).first()
    await expect(moveButton).toBeVisible({ timeout: 5_000 })
    await moveButton.click()

    // 「フォルダから外す」をクリック
    await expect(page.getByText('フォルダから外す')).toBeVisible({ timeout: 5_000 })
    await page.getByText('フォルダから外す').click()
  })
})
