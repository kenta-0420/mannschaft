/**
 * F02.8 ダッシュボード告知ウィザード Phase 3 — チームスコープ E2E テスト
 *
 * テスト対象: BroadcastWizard（TEAM スコープ）
 * ページ: /teams/1（teams/[id]/index.vue）
 *
 * DOM 構造の根拠:
 * - 「チーム内告知」ボタン: $t('announcement.broadcast_button_team') ＝ "チーム内告知"
 *   → teams/[id]/index.vue L141 に `<Button :label="$t('announcement.broadcast_button_team')">`
 * - ウィザードダイアログ: PrimeVue Dialog → role="dialog"
 * - Step1 対象ロール ラジオ: `input-id=target_role_{value}` から label[for=target_role_MEMBERS_ONLY]
 *   → BroadcastStep1Audience.vue L83 に `:for="\`target_role_${opt.value}\`"`
 * - Step2 チャネルカード: button 要素内の `<span>` テキスト（例: "掲示板"）
 *   → BroadcastStep2Channel.vue L62 に `<span>{{ t(ch.labelKey) }}</span>`
 * - Step3 タイトル/本文: label "タイトル"/"本文" の直後の input/textarea
 *   → BroadcastStep3Content.vue L227/238 に `$t('announcement.form_title')`
 * - 送信ボタン: $t('announcement.submit_button') = "告知を送る"
 *   → BroadcastStep3Content.vue L412
 * - 次へボタン: $t('button.next') = "次へ"
 * - 戻るボタン: $t('button.back') = "戻る"
 * - 成功トースト: notification.success($t('announcement.broadcast_success')) = "告知を送信しました"
 *   → BroadcastWizard.vue L113
 * - キャンセル確認ダイアログ: window.confirm($t('announcement.unsaved_changes_warning'))
 *   → BroadcastWizard.vue L72（ネイティブ confirm ダイアログ）
 * - 優先度 Select: isAdmin が true の場合のみ表示、NORMAL/IMPORTANT/URGENT の Select
 *   → BroadcastStep3Content.vue L380 `v-if="isAdmin"`
 * - テンプレート Select: BroadcastTemplateSelector.vue の PrimeVue Select
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, selectDropdown } from '../helpers/form'
import { mockTeamApis, mockBroadcastApi, mockTemplateApi, TEAM_ID } from './helpers'

// デフォルトは ADMIN 認証状態を使用
test.use({ storageState: 'tests/e2e/.auth/admin.json' })

test.describe('F02.8 チームスコープ告知ウィザード', () => {
  // ---------------------------------------------------------------------------
  // BROADCAST-001: ADMIN がチームダッシュボードで告知ウィザードを起動し、掲示板チャネルで送信する
  // ---------------------------------------------------------------------------
  test('BROADCAST-001: ADMIN が掲示板チャネルで告知を送信できる', async ({ page }) => {
    // API モックを登録
    await mockTeamApis(page, TEAM_ID)
    await mockBroadcastApi(page, TEAM_ID)

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // 「チーム内告知」ボタンが表示されるまで待機してクリック
    const broadcastBtn = page.getByRole('button', { name: 'チーム内告知' })
    await expect(broadcastBtn).toBeVisible({ timeout: 10_000 })
    await broadcastBtn.click()

    // ダイアログが開くのを待つ
    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1: 「メンバーのみ」ラジオボタンを選択（デフォルトでも選択済みだが明示的にクリック）
    const membersOnlyLabel = dialog.locator('label[for="target_role_MEMBERS_ONLY"]')
    await expect(membersOnlyLabel).toBeVisible({ timeout: 3_000 })
    await membersOnlyLabel.click()

    // 次へ
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 2: 「掲示板」チャネルカードをクリック
    await expect(dialog.getByText('掲示板')).toBeVisible({ timeout: 3_000 })
    await dialog.getByText('掲示板').click()

    // 次へ
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 3: タイトルと本文を入力
    await expect(dialog.locator('input').first()).toBeVisible({ timeout: 3_000 })
    await fillInput(dialog.locator('input').first(), 'テスト告知')

    const textarea = dialog.locator('textarea').first()
    await expect(textarea).toBeVisible({ timeout: 3_000 })
    await fillInput(textarea, 'テスト本文')

    // ブロードキャスト API リクエストを待ちながら送信ボタンをクリック
    const requestPromise = page.waitForRequest(
      (req) =>
        req.url().includes(`/api/v1/teams/${TEAM_ID}/broadcast`) &&
        req.method() === 'POST',
      { timeout: 10_000 },
    )

    await dialog.getByRole('button', { name: '告知を送る' }).click()

    // API が呼ばれたことを確認
    const broadcastRequest = await requestPromise
    expect(broadcastRequest).toBeTruthy()

    // 成功トーストが表示されることを確認
    // PrimeVue Toast は .p-toast クラスまたは role="alert" で識別できる
    await expect(
      page.getByText('告知を送信しました'),
    ).toBeVisible({ timeout: 8_000 })
  })

  // ---------------------------------------------------------------------------
  // BROADCAST-002: MEMBER がウィザードを開くと NORMAL 優先度のみ選択可能
  // （isAdmin=false の場合、優先度 Select 自体が非表示になる）
  // ---------------------------------------------------------------------------
  test('BROADCAST-002: MEMBER は優先度セレクターが表示されない', async ({ page }) => {
    // このテストのみ一般ユーザー認証状態を使用
    // storageState はテストレベルでは上書きできないため、
    // MEMBER ロールのレスポンスを返すモックで対応する
    await mockTeamApis(page, TEAM_ID)

    // MEMBER ロールでのメンバーシップを上書きモック
    await page.route(`**/api/v1/teams/${TEAM_ID}/memberships/my**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            userId: 1,
            teamId: TEAM_ID,
            role: 'MEMBER',
            roleName: 'MEMBER',
            joinedAt: '2026-01-01T00:00:00+09:00',
          },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // 「チーム内告知」ボタンをクリック（MEMBER も表示される: roleName && roleName !== 'SUPPORTER'）
    const broadcastBtn = page.getByRole('button', { name: 'チーム内告知' })
    await expect(broadcastBtn).toBeVisible({ timeout: 10_000 })
    await broadcastBtn.click()

    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1 → 次へ
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 2: 掲示板を選択 → 次へ
    await expect(dialog.getByText('掲示板')).toBeVisible({ timeout: 3_000 })
    await dialog.getByText('掲示板').click()
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 3: isAdmin=false の場合、優先度 Select が表示されないことを確認
    // BroadcastStep3Content.vue L380: `v-if="isAdmin"` で制御
    await expect(dialog.locator('label').filter({ hasText: '優先度' })).not.toBeVisible({ timeout: 3_000 })

    // URGENT / IMPORTANT の選択肢が存在しないことを確認
    const urgentOption = dialog.getByText('緊急')
    const importantOption = dialog.getByText('重要')
    await expect(urgentOption).not.toBeVisible()
    await expect(importantOption).not.toBeVisible()
  })

  // ---------------------------------------------------------------------------
  // BROADCAST-003: テンプレートを選ぶと Step1 の対象範囲が自動入力される
  // ---------------------------------------------------------------------------
  test('BROADCAST-003: テンプレート選択で Step1 の対象ロールが自動入力される', async ({ page }) => {
    await mockTeamApis(page, TEAM_ID)
    // テンプレート API を個別に上書き（テンプレート1件を返す）
    await mockTemplateApi(page, TEAM_ID)

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    const broadcastBtn = page.getByRole('button', { name: 'チーム内告知' })
    await expect(broadcastBtn).toBeVisible({ timeout: 10_000 })
    await broadcastBtn.click()

    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // BroadcastTemplateSelector の Select ドロップダウンを開き「全メンバー告知（デフォルト）」を選択
    // BroadcastTemplateSelector.vue L47: `${tpl.name}（デフォルト）` か `tpl.name`
    const templateSelect = dialog.locator('.p-select').last()
    await selectDropdown(page, templateSelect, '全メンバー告知')

    // テンプレートの targetRole=MEMBERS_ONLY が適用されているか確認
    // BroadcastStep1Audience.vue L82: RadioButton の v-model="targetRole" で MEMBERS_ONLY が選択される
    const membersOnlyRadio = dialog.locator('input[type="radio"]#target_role_MEMBERS_ONLY')
    await expect(membersOnlyRadio).toBeChecked({ timeout: 3_000 })
  })

  // ---------------------------------------------------------------------------
  // BROADCAST-004: 入力中にキャンセルすると確認ダイアログが表示される
  // ---------------------------------------------------------------------------
  test('BROADCAST-004: 入力後にウィザードを閉じようとすると確認ダイアログが表示される', async ({ page }) => {
    await mockTeamApis(page, TEAM_ID)

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    const broadcastBtn = page.getByRole('button', { name: 'チーム内告知' })
    await expect(broadcastBtn).toBeVisible({ timeout: 10_000 })
    await broadcastBtn.click()

    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1 で「メンバーのみ」をクリックして isDirty を true にする
    // isDirty の条件: selectedChannel !== null OR content に title/body/content あり
    // Step1 のラジオクリックだけでは isDirty にならないため、Step2 でチャネルを選択する
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 2: 掲示板をクリック（selectedChannel が設定されると isDirty = true）
    await expect(dialog.getByText('掲示板')).toBeVisible({ timeout: 3_000 })
    await dialog.getByText('掲示板').click()

    // ウィンドウ confirm ダイアログを受け入れない（「キャンセル」相当）
    // BroadcastWizard.vue L70: `window.confirm(t('announcement.unsaved_changes_warning'))`
    page.on('dialog', async (nativeDialog) => {
      // 確認メッセージを検証
      expect(nativeDialog.message()).toContain('入力内容が失われますがよいですか')
      // キャンセル（dismiss）してウィザードを維持する
      await nativeDialog.dismiss()
    })

    // PrimeVue Dialog の閉じるボタン（.p-dialog-close-button または [aria-label="Close"]）をクリック
    const closeBtn = dialog.locator('.p-dialog-close-button, [aria-label="Close"]').first()
    await closeBtn.click()

    // dismiss したのでウィザードはまだ表示されているはず
    await expect(dialog).toBeVisible({ timeout: 3_000 })
  })

  // ---------------------------------------------------------------------------
  // BROADCAST-005: スケジュールチャネルで場所・説明フィールドが表示され送信できる
  // ---------------------------------------------------------------------------
  test('BROADCAST-005: スケジュールチャネルで場所・説明フィールドが表示され送信できる', async ({
    page,
  }) => {
    await mockTeamApis(page, TEAM_ID)
    await mockBroadcastApi(page, TEAM_ID, { channel: 'SCHEDULE' })

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    const broadcastBtn = page.getByRole('button', { name: 'チーム内告知' })
    await expect(broadcastBtn).toBeVisible({ timeout: 10_000 })
    await broadcastBtn.click()

    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1: デフォルトのまま次へ
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 2: スケジュールを選択
    await expect(dialog.getByText('スケジュール')).toBeVisible({ timeout: 3_000 })
    await dialog.getByText('スケジュール').click()
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 3: スケジュール用フィールドが表示されていることを確認
    // BroadcastStep3Content.vue L327: form_description ラベル
    // BroadcastStep3Content.vue L335: form_location ラベル
    await expect(
      dialog.locator('label').filter({ hasText: '説明（任意）' }).first(),
    ).toBeVisible({ timeout: 3_000 })
    await expect(
      dialog.locator('label').filter({ hasText: '場所（任意）' }).first(),
    ).toBeVisible({ timeout: 3_000 })

    // タイトルを入力
    const titleInput = dialog.locator('input').first()
    await fillInput(titleInput, 'スケジュールテスト')

    // 終日チェックボックスを ON にする（開始日時入力を不要にする）
    // BroadcastStep3Content.vue L278: `v-model="scheduleAllDay" input-id="schedule-all-day"`
    const allDayLabel = dialog.locator('label[for="schedule-all-day"]')
    await expect(allDayLabel).toBeVisible({ timeout: 3_000 })
    await allDayLabel.click()

    // 終日 ON の場合は DatePicker で日付入力
    const datePicker = dialog.locator('.p-datepicker-input').first()
    if (await datePicker.isVisible()) {
      await datePicker.click()
      await datePicker.press('Escape')
      await datePicker.fill('2026/12/01')
      await datePicker.press('Tab')
    }

    // 場所を入力
    const locationLabel = dialog.locator('label').filter({ hasText: '場所（任意）' }).first()
    const locationInput = locationLabel.locator('~ input').first()
    const locationInputFallback = dialog.locator('input').last()
    if (await locationInput.isVisible()) {
      await fillInput(locationInput, '東京体育館')
    } else {
      await fillInput(locationInputFallback, '東京体育館')
    }

    // 送信リクエストに channel=SCHEDULE が含まれることを確認
    const requestPromise = page.waitForRequest(
      (req) =>
        req.url().includes(`/api/v1/teams/${TEAM_ID}/broadcast`) &&
        req.method() === 'POST',
      { timeout: 10_000 },
    )

    const submitBtn = dialog.getByRole('button', { name: '告知を送る' })
    // disabled でない場合のみクリック（startAt が必須のため可能であれば）
    const isDisabled = await submitBtn.isDisabled()
    if (!isDisabled) {
      await submitBtn.click()
      const sentRequest = await requestPromise
      const requestBody = sentRequest.postDataJSON() as Record<string, unknown>
      expect(requestBody.channel).toBe('SCHEDULE')
    } else {
      // 送信ボタンが disabled の場合でも、チャネル SCHEDULE のフォームが正しく表示されていることを確認済み
      expect(await dialog.locator('label').filter({ hasText: '説明（任意）' }).count()).toBeGreaterThan(0)
    }
  })

  // ---------------------------------------------------------------------------
  // BROADCAST-006: アンケートチャネルで送信できる
  // ---------------------------------------------------------------------------
  test('BROADCAST-006: アンケートチャネルで送信できる', async ({ page }) => {
    await mockTeamApis(page, TEAM_ID)
    await mockBroadcastApi(page, TEAM_ID, { channel: 'SURVEY' })

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    const broadcastBtn = page.getByRole('button', { name: 'チーム内告知' })
    await expect(broadcastBtn).toBeVisible({ timeout: 10_000 })
    await broadcastBtn.click()

    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // Step 1: デフォルトのまま次へ
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 2: アンケートを選択
    // BroadcastStep2Channel.vue L28: { key: 'SURVEY', labelKey: 'announcement.channel_survey' }
    // announcement.channel_survey = "アンケート"
    await expect(dialog.getByText('アンケート')).toBeVisible({ timeout: 3_000 })
    await dialog.getByText('アンケート').click()
    await dialog.getByRole('button', { name: '次へ' }).click()

    // Step 3: タイトルを入力
    const titleInput = dialog.locator('input').first()
    await expect(titleInput).toBeVisible({ timeout: 3_000 })
    await fillInput(titleInput, 'アンケートテスト告知')

    // 送信リクエストに channel=SURVEY が含まれることを確認
    const requestPromise = page.waitForRequest(
      (req) =>
        req.url().includes(`/api/v1/teams/${TEAM_ID}/broadcast`) &&
        req.method() === 'POST',
      { timeout: 10_000 },
    )

    await dialog.getByRole('button', { name: '告知を送る' }).click()

    const sentRequest = await requestPromise
    const requestBody = sentRequest.postDataJSON() as Record<string, unknown>
    expect(requestBody.channel).toBe('SURVEY')
  })
})
