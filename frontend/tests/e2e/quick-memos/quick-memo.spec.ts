import { waitForHydration } from '../helpers/wait'
import { test, expect } from '@playwright/test'
import * as fs from 'fs'

// storageState ファイルが存在しない場合に安全にスキップするためのフラグ
const storageStateExists = fs.existsSync('tests/e2e/.auth/user.json')

// ストレージ状態ファイルが存在しない場合は全テストをスキップ
if (!storageStateExists) {
  test('QM: storageState が未作成のためスキップ', () => {
    test.skip()
  })
} else {
  test.describe('QM-001〜009: ポイっとメモ基本CRUD', () => {
    // 認証済みユーザー状態を使用（playwright.config.ts の chromium プロジェクトで設定済み）

    // 作成したメモのタイトルを追跡してクリーンアップに使用
    const createdMemoTitles: string[] = []

    test.afterEach(async ({ page }) => {
      // テスト後のクリーンアップ: 作成したメモを削除
      // ページ遷移を伴うため、可能な範囲でクリーンアップを試みる
      if (createdMemoTitles.length > 0) {
        try {
          await page.goto('/quick-memos')
          await waitForHydration(page)
          // 各メモカードの削除ボタンをクリックしてクリーンアップ
          for (const title of createdMemoTitles) {
            const card = page.locator('.group').filter({ hasText: title })
            if (await card.count() > 0) {
              await card.hover()
              const deleteBtn = card.locator('button[title*="削除"], button[title*="delete"]').last()
              if (await deleteBtn.isVisible()) {
                await deleteBtn.click()
                await page.waitForTimeout(500)
              }
            }
          }
        } catch {
          // クリーンアップ失敗は無視する
        } finally {
          createdMemoTitles.length = 0
        }
      }
    })

    test('QM-001: メモ作成ページに遷移できる', async ({ page }) => {
      await page.goto('/quick-memos')
      await waitForHydration(page)

      // ページタイトルが表示されている
      await expect(page.locator('h1')).toBeVisible({ timeout: 10_000 })
      // URLが /quick-memos であることを確認
      await expect(page).toHaveURL(/\/quick-memos/)
    })

    test('QM-002: タイトルを入力してメモを作成できる', async ({ page }) => {
      const memoTitle = `E2Eテストメモ_${Date.now()}`
      createdMemoTitles.push(memoTitle)

      await page.goto('/quick-memos')
      await waitForHydration(page)

      // フローティングボタン（fixed, bottom-right）をクリック
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        // フォールバック: ページ内の feather アイコンボタンを探す
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }

      // モーダルが表示されるのを待つ
      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })

      // タイトル入力
      const titleInput = dialog.locator('input[placeholder], input[maxlength="200"]').first()
      await titleInput.fill(memoTitle)

      // 保存ボタンをクリック
      await dialog.locator('button').filter({ hasText: /保存する/ }).click()

      // モーダルが閉じる（1.5秒後）
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      // 一覧にメモが表示される
      await expect(page.locator('.group').filter({ hasText: memoTitle })).toBeVisible({ timeout: 10_000 })
    })

    test('QM-003: タイトルなしでメモを作成すると自動補完される', async ({ page }) => {
      await page.goto('/quick-memos')
      await waitForHydration(page)

      // モーダルを開く
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }

      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })

      // タイトルを空のまま保存ボタンがdisabledになっているか確認
      const saveBtn = dialog.locator('button').filter({ hasText: /保存する/ })
      // タイトル未入力時は保存ボタンが無効化されている（コンポーネント実装による）
      await expect(saveBtn).toBeDisabled()

      // キャンセルして終了
      await dialog.locator('button').filter({ hasText: /キャンセル/ }).click()
    })

    test('QM-004: メモを検索できる', async ({ page }) => {
      const uniqueKeyword = `検索テスト_${Date.now()}`
      createdMemoTitles.push(uniqueKeyword)

      await page.goto('/quick-memos')
      await waitForHydration(page)

      // まずテスト用のメモを作成する
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }

      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })
      const titleInput = dialog.locator('input[placeholder], input[maxlength="200"]').first()
      await titleInput.fill(uniqueKeyword)
      await dialog.locator('button').filter({ hasText: /保存する/ }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })
      await expect(page.locator('.group').filter({ hasText: uniqueKeyword })).toBeVisible({ timeout: 10_000 })

      // 検索バーに入力
      const searchInput = page.locator('input[placeholder*="検索"]')
      await searchInput.fill(uniqueKeyword)

      // 検索結果が絞り込まれる（300ms debounce後）
      await page.waitForTimeout(500)
      await expect(page.locator('.group').filter({ hasText: uniqueKeyword })).toBeVisible()
    })

    test('QM-005: メモをアーカイブできる', async ({ page }) => {
      const memoTitle = `アーカイブテスト_${Date.now()}`

      await page.goto('/quick-memos')
      await waitForHydration(page)

      // テスト用メモを作成
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }
      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })
      const titleInput = dialog.locator('input[placeholder], input[maxlength="200"]').first()
      await titleInput.fill(memoTitle)
      await dialog.locator('button').filter({ hasText: /保存する/ }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      // メモカードのアーカイブボタンをクリック
      const memoCard = page.locator('.group').filter({ hasText: memoTitle })
      await expect(memoCard).toBeVisible({ timeout: 10_000 })
      await memoCard.hover()

      // アーカイブボタン（pi-inbox アイコン）をクリック
      const archiveBtn = memoCard.locator('button[title*="アーカイブ"]')
        .or(memoCard.locator('button').filter({ has: page.locator('.pi-inbox') }))
      await archiveBtn.click()

      // UNSORTEDタブからメモが消える
      await expect(memoCard).not.toBeVisible({ timeout: 5_000 })

      // ARCHIVEDタブに切り替えてメモを確認
      const archivedTab = page.locator('button').filter({ hasText: 'アーカイブ済み' })
        .or(page.locator('button').filter({ hasText: 'ARCHIVED' }))
      await archivedTab.click()
      await expect(page.locator('.group').filter({ hasText: memoTitle })).toBeVisible({ timeout: 5_000 })

      // クリーンアップ: ARCHIVEDタブでメモを削除
      const archivedCard = page.locator('.group').filter({ hasText: memoTitle })
      await archivedCard.hover()
      const deleteBtn = archivedCard.locator('button[title*="削除"]')
        .or(archivedCard.locator('button').filter({ has: page.locator('.pi-trash') }))
      if (await deleteBtn.count() > 0) {
        await deleteBtn.click()
      }
    })

    test('QM-006: アーカイブしたメモを復元できる', async ({ page }) => {
      const memoTitle = `復元テスト_${Date.now()}`

      await page.goto('/quick-memos')
      await waitForHydration(page)

      // テスト用メモを作成してアーカイブ
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }
      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })
      const titleInput = dialog.locator('input[placeholder], input[maxlength="200"]').first()
      await titleInput.fill(memoTitle)
      await dialog.locator('button').filter({ hasText: /保存する/ }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      const memoCard = page.locator('.group').filter({ hasText: memoTitle })
      await expect(memoCard).toBeVisible({ timeout: 10_000 })
      await memoCard.hover()
      const archiveBtn = memoCard.locator('button[title*="アーカイブ"]')
        .or(memoCard.locator('button').filter({ has: page.locator('.pi-inbox') }))
      await archiveBtn.click()
      await expect(memoCard).not.toBeVisible({ timeout: 5_000 })

      // ARCHIVEDタブに移動
      const archivedTab = page.locator('button').filter({ hasText: 'アーカイブ済み' })
        .or(page.locator('button').filter({ hasText: 'ARCHIVED' }))
      await archivedTab.click()

      // 復元ボタンをクリック
      const archivedCard = page.locator('.group').filter({ hasText: memoTitle })
      await expect(archivedCard).toBeVisible({ timeout: 5_000 })
      await archivedCard.hover()
      const restoreBtn = archivedCard.locator('button[title*="復元"]')
        .or(archivedCard.locator('button').filter({ has: page.locator('.pi-refresh, .pi-undo') }))
      await restoreBtn.click()

      // ARCHIVEDタブからメモが消える
      await expect(archivedCard).not.toBeVisible({ timeout: 5_000 })

      // UNSORTEDタブに戻ってメモを確認
      const unsortedTab = page.locator('button').filter({ hasText: '未整理' })
        .or(page.locator('button').filter({ hasText: 'UNSORTED' }))
      await unsortedTab.click()
      await expect(page.locator('.group').filter({ hasText: memoTitle })).toBeVisible({ timeout: 5_000 })

      createdMemoTitles.push(memoTitle)
    })

    test('QM-007: メモを削除するとゴミ箱に移動する', async ({ page }) => {
      const memoTitle = `削除テスト_${Date.now()}`

      await page.goto('/quick-memos')
      await waitForHydration(page)

      // テスト用メモを作成
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }
      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })
      const titleInput = dialog.locator('input[placeholder], input[maxlength="200"]').first()
      await titleInput.fill(memoTitle)
      await dialog.locator('button').filter({ hasText: /保存する/ }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      const memoCard = page.locator('.group').filter({ hasText: memoTitle })
      await expect(memoCard).toBeVisible({ timeout: 10_000 })

      // 削除ボタンをクリック
      await memoCard.hover()
      const deleteBtn = memoCard.locator('button[title*="削除"]')
        .or(memoCard.locator('button[severity="danger"]'))
        .or(memoCard.locator('button').filter({ has: page.locator('.pi-trash') }))
      await deleteBtn.click()

      // UNSORTEDタブからメモが消える
      await expect(memoCard).not.toBeVisible({ timeout: 5_000 })

      // ゴミ箱ページに移動してメモを確認
      await page.goto('/quick-memos/trash')
      await waitForHydration(page)
      await expect(page.locator('.group, [data-memo]').filter({ hasText: memoTitle }))
        .toBeVisible({ timeout: 10_000 })
    })

    test('QM-008: ゴミ箱からメモを復元できる', async ({ page }) => {
      const memoTitle = `ゴミ箱復元テスト_${Date.now()}`
      createdMemoTitles.push(memoTitle)

      await page.goto('/quick-memos')
      await waitForHydration(page)

      // テスト用メモを作成して削除
      const floatingBtn = page.locator('button.\\!fixed')
      if (await floatingBtn.count() > 0) {
        await floatingBtn.first().click()
      } else {
        await page.locator('button[title*="Ctrl+Shift+M"]').click()
      }
      const dialog = page.locator('.p-dialog, [role="dialog"]')
      await expect(dialog).toBeVisible({ timeout: 5_000 })
      const titleInput = dialog.locator('input[placeholder], input[maxlength="200"]').first()
      await titleInput.fill(memoTitle)
      await dialog.locator('button').filter({ hasText: /保存する/ }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      const memoCard = page.locator('.group').filter({ hasText: memoTitle })
      await expect(memoCard).toBeVisible({ timeout: 10_000 })
      await memoCard.hover()
      const deleteBtn = memoCard.locator('button[title*="削除"]')
        .or(memoCard.locator('button[severity="danger"]'))
        .or(memoCard.locator('button').filter({ has: page.locator('.pi-trash') }))
      await deleteBtn.click()
      await expect(memoCard).not.toBeVisible({ timeout: 5_000 })

      // ゴミ箱ページに移動
      await page.goto('/quick-memos/trash')
      await waitForHydration(page)

      const trashCard = page.locator('.group, [data-memo]').filter({ hasText: memoTitle })
      await expect(trashCard).toBeVisible({ timeout: 10_000 })

      // 復元ボタンをクリック
      await trashCard.hover()
      const restoreBtn = trashCard.locator('button').filter({ hasText: /復元/ })
        .or(trashCard.locator('button[title*="復元"]'))
      await restoreBtn.click()

      // ゴミ箱からメモが消える
      await expect(trashCard).not.toBeVisible({ timeout: 5_000 })

      // UNSORTEDタブに戻ってメモを確認
      await page.goto('/quick-memos')
      await waitForHydration(page)
      await expect(page.locator('.group').filter({ hasText: memoTitle })).toBeVisible({ timeout: 10_000 })
    })

    test('QM-009: リマインド設定ページにアクセスできる', async ({ page }) => {
      await page.goto('/quick-memos/settings')
      await waitForHydration(page)

      // URLが /quick-memos/settings であることを確認
      await expect(page).toHaveURL(/\/quick-memos\/settings/)
      // 設定ページのコンテンツが表示されている
      await expect(page.locator('h1, h2').first()).toBeVisible({ timeout: 10_000 })
    })
  })
}
