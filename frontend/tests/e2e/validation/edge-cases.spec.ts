import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, clearAndFillInput, waitForDialog } from '../helpers/form'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

/**
 * VALIDATION-DEEP edge-cases: 入力エッジケースの深掘り。
 *
 * 既存の TODO 作成ダイアログ (TodoForm.vue) を共通の検証対象として使用する。
 * - title: PrimeVue InputText（必須・trim あり）
 * - description: PrimeVue Textarea（任意）
 *
 * 極端な入力値（超長文・特殊文字・絵文字・空白文字・マイナス数値風文字列）を投入し、
 * クライアント側のバリデーション挙動とサーバー送信値の整合性を確認する。
 */

test.describe('VALIDATION-DEEP edge-cases: 入力エッジケース深掘り', () => {
  test.beforeEach(async ({ page }) => {
    // チーム基本情報・権限・配下API（一覧）はすべて空でモック
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('VAL-DEEP-edge-001: 超長文(10000文字)のタイトルでも入力欄に反映される', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })

    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    // pressSequentially は非常に遅いため、長文は evaluate で v-model に直接流し込む
    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    const longText = 'あ'.repeat(10_000)
    await titleInput.click()
    await titleInput.evaluate((el, value) => {
      const input = el as HTMLInputElement
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')
      setter?.set?.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
    }, longText)

    // 入力欄には 10000 文字すべてが反映されている（クライアント側で切り捨てない）
    await expect(titleInput).toHaveValue(longText)
  })

  test('VAL-DEEP-edge-002: HTML/SQLインジェクション風の文字列がエスケープされて表示される', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    // script タグ・SQL の引用符・HTML タグを含むペイロード
    const payload = '<script>alert("xss")</script>\' OR 1=1 --'
    await fillInput(titleInput, payload)

    // input 要素の value としてはそのまま保持される（DOM テキストノードに展開されない）
    await expect(titleInput).toHaveValue(payload)

    // ダイアログ内に <script> 要素が新規追加されていないことを保証する
    const scriptCountInDialog = await dialog.locator('script').count()
    expect(scriptCountInDialog).toBe(0)
  })

  test('VAL-DEEP-edge-003: 絵文字を含むタイトルが正しく POST 本文に含まれる', async ({
    page,
  }) => {
    let postBody: Record<string, unknown> | null = null
    // mockTeamFeatureApis の catch-all を上書きして、POST 時にだけ送信内容を捕捉する
    await page.route(`**/api/v1/teams/${TEAM_ID}/todos`, async (route) => {
      const req = route.request()
      if (req.method() === 'POST') {
        postBody = req.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 1,
              scopeType: 'team',
              scopeId: TEAM_ID,
              title: postBody.title,
              description: null,
              status: 'OPEN',
              priority: 'MEDIUM',
              dueDate: null,
              dueTime: null,
              daysRemaining: null,
              completedAt: null,
              completedBy: null,
              createdBy: { id: 1, displayName: 'テストユーザー' },
              sortOrder: 0,
              assignees: [],
              createdAt: '2026-04-07T00:00:00Z',
              updatedAt: '2026-04-07T00:00:00Z',
            },
          }),
        })
        return
      }
      // GET（一覧）など他のメソッドは空配列で応答
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    // ZWJ シーケンス・絵文字バリエーションを含むタイトル
    const emojiTitle = 'リリース 🎉🚀 完了 👨‍👩‍👧‍👦'
    await fillInput(titleInput, emojiTitle)

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/teams/${TEAM_ID}/todos`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await dialog.getByRole('button', { name: '作成' }).click()
    await respPromise

    // 絵文字がそのまま POST 本文に含まれていること
    expect(postBody).not.toBeNull()
    expect((postBody as unknown as { title: string }).title).toBe(emojiTitle)
  })

  test('VAL-DEEP-edge-004: 空白・改行のみのタイトルは trim 後にバリデーションエラーになる', async ({
    page,
  }) => {
    // POST が呼ばれないことの検証用
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/todos`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    // スペース＋タブ風の半角空白のみ（input 要素には改行は入らないので空白で代用）
    await fillInput(titleInput, '     ')

    await dialog.getByRole('button', { name: '作成' }).click()

    // TodoForm.submit() の冒頭で title.trim() の空判定によりエラーが立つ
    await expect(dialog.getByText('タイトルは必須です')).toBeVisible({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
    // ダイアログは閉じない
    await expect(dialog).toBeVisible()
  })

  test('VAL-DEEP-edge-005: マイナス記号を含む文字列を入力しても InputText がそのまま受け入れる', async ({
    page,
  }) => {
    // PrimeVue の InputText（type=text）はマイナス記号を含む任意の文字列を受け付けるため、
    // フロント側のフィルタリングは行われないことを保証する。
    // クリア・再入力でも値が壊れないことを副次的に確認する。
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'TODO作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('TODOのタイトル')
    await fillInput(titleInput, '-100 円相当のタスク')
    await expect(titleInput).toHaveValue('-100 円相当のタスク')

    // クリアして純粋な負数文字列に置き換えても保持される
    await clearAndFillInput(titleInput, '-9999')
    await expect(titleInput).toHaveValue('-9999')
  })
})
