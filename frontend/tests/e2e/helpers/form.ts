import type { Locator, Page } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * PrimeVue InputText / InputNumber / Textarea 用の入力ヘルパー。
 *
 * fill() は v-model の oninput ハンドラを発火させない場合があるため、
 * click → pressSequentially でキー入力イベントを正しく発生させる。
 */
export async function fillInput(locator: Locator, value: string): Promise<void> {
  await locator.click()
  await locator.pressSequentially(value, { delay: 10 })
}

/**
 * PrimeVue InputText 等を空にしてから値を入力する。
 * 既存値がある場合のリセット用。
 */
export async function clearAndFillInput(locator: Locator, value: string): Promise<void> {
  await locator.click()
  await locator.press('ControlOrMeta+A')
  await locator.press('Delete')
  await locator.pressSequentially(value, { delay: 10 })
}

/**
 * PrimeVue Password 用の入力ヘルパー。
 * feedback=true の場合、入力後に表示される強度オーバーレイが
 * 後続フィールドのクリックを妨害するため Tab で閉じる必要がある。
 */
export async function fillPassword(
  locator: Locator,
  value: string,
  options: { closeFeedback?: boolean } = {},
): Promise<void> {
  await locator.click()
  await locator.pressSequentially(value, { delay: 10 })
  if (options.closeFeedback) {
    await locator.page().keyboard.press('Tab')
  }
}

/**
 * PrimeVue Select / Dropdown 用の選択ヘルパー。
 * トリガーをクリックして listbox を開き、optionText に一致する項目をクリックする。
 */
export async function selectDropdown(
  page: Page,
  trigger: Locator,
  optionText: string | RegExp,
): Promise<void> {
  await trigger.click()
  const listbox = page.locator('[role="listbox"]').last()
  await expect(listbox).toBeVisible({ timeout: 5_000 })
  await listbox.getByText(optionText, { exact: false }).first().click()
}

/**
 * PrimeVue DatePicker 用の選択ヘルパー。
 * show-icon 付きの DatePicker はクリックするとカレンダーパネルが開くため、
 * Escape でパネルを閉じた後に fill() で直接入力する。
 * showTime のときは "yyyy/mm/dd hh:mm" 形式で渡すこと。
 */
export async function pickDate(locator: Locator, dateString: string): Promise<void> {
  // クリックしてフォーカスを当てる（カレンダーパネルが開く場合あり）
  await locator.click()
  // Escape でカレンダーパネルを閉じてから直接入力する
  await locator.press('Escape')
  await locator.fill(dateString)
  // Tab で確定（v-model にバインド）
  await locator.press('Tab')
}

/**
 * 送信ボタンをクリックして、URL 変化または API レスポンスのいずれかを待つ。
 *
 * 使用例:
 *   await submitAndWait(page, 'ログイン', { urlPattern: /\/dashboard/ })
 *   await submitAndWait(page, '作成', { apiPath: '/api/v1/teams/1/todos' })
 */
export async function submitAndWait(
  page: Page,
  buttonName: string | RegExp,
  options: { urlPattern?: RegExp; apiPath?: string; timeout?: number } = {},
): Promise<void> {
  const timeout = options.timeout ?? 15_000
  const promises: Promise<unknown>[] = []
  if (options.urlPattern) {
    promises.push(page.waitForURL(options.urlPattern, { timeout }))
  }
  if (options.apiPath) {
    promises.push(
      page.waitForResponse(
        (resp) => resp.url().includes(options.apiPath!) && resp.request().method() !== 'OPTIONS',
        { timeout },
      ),
    )
  }
  await page.getByRole('button', { name: buttonName }).click()
  if (promises.length > 0) {
    await Promise.all(promises)
  }
}

/**
 * PrimeVue Checkbox / ToggleSwitch をトグルする。
 * 内部の input ではなくラッパー要素をクリックする必要がある。
 */
export async function toggleCheckbox(locator: Locator): Promise<void> {
  await locator.click()
}

/**
 * PrimeVue Dialog が開くのを待つ。
 * トリガークリック後に [role="dialog"] の出現を確認する。
 */
export async function waitForDialog(page: Page): Promise<Locator> {
  const dialog = page.locator('[role="dialog"]').last()
  await expect(dialog).toBeVisible({ timeout: 5_000 })
  return dialog
}
