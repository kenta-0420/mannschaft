import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F09.17 残課題 4 — 公開 unsubscribe SPA happy path smoke E2E。
 *
 * <p>メール末尾リンク → /ads/unsubscribe?token=XYZ で SPA を開き、
 * チャネル選択 → 「停止する」ボタン → 完了画面までの 3 ステップ動線を検証する。</p>
 */

const TOKEN = 'jwt-stub-token'

interface UnsubscribePostBody {
  token?: string
  channels?: string[]
}

test.describe('F09.17 残課題 4: 公開 unsubscribe SPA (smoke)', () => {
  test('token → チャネル選択 → POST → 完了画面までの happy path', async ({ page }) => {
    let postCalled = false
    let postBody: UnsubscribePostBody | null = null

    await page.route('**/api/v1/ads/unsubscribe', async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        postCalled = true
        try {
          postBody = JSON.parse(route.request().postData() ?? '{}') as UnsubscribePostBody
        }
        catch {
          postBody = null
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            disabledChannels: postBody?.channels ?? [],
            remainingActiveChannels: [],
            messageKey: 'advertising.unsubscribe_spa.success_message',
          }),
        })
        return
      }
      await route.continue()
    })

    await page.goto(`/ads/unsubscribe?token=${TOKEN}`)
    await waitForHydration(page)

    // 初期表示: 4 チャネル全て stop 候補としてチェック ON
    await expect(page.getByTestId('unsubscribe-channel-ANNOUNCEMENT')).toBeChecked()
    await expect(page.getByTestId('unsubscribe-channel-EMAIL')).toBeChecked()
    await expect(page.getByTestId('unsubscribe-channel-PUSH')).toBeChecked()
    await expect(page.getByTestId('unsubscribe-channel-BANNER')).toBeChecked()

    // PUSH / BANNER のチェックを外して 2 チャネルだけ停止する
    await page.getByTestId('unsubscribe-channel-PUSH').uncheck()
    await page.getByTestId('unsubscribe-channel-BANNER').uncheck()

    // 「停止する」ボタンクリック
    await page.getByTestId('unsubscribe-submit').click()

    // 完了画面表示まで待機
    await expect(page.getByText(/停止しました|Unsubscribed|중지|cancelad|abgemeldet|已停止/i))
      .toBeVisible({ timeout: 5_000 })

    // POST が走り、停止対象 = ANNOUNCEMENT + EMAIL のみであること
    expect(postCalled).toBe(true)
    const captured = postBody as UnsubscribePostBody | null
    expect(captured?.token).toBe(TOKEN)
    expect(captured?.channels?.slice().sort()).toEqual(['ANNOUNCEMENT', 'EMAIL'])
  })
})
