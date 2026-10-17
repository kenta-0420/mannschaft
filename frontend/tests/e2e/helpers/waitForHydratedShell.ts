import { expect, type Locator, type Page } from '@playwright/test'
import { waitForHydration } from './wait'

/** 寸法の回帰を、アプリ未起動・未ハイドレーションと区別するための前提。 */
export async function waitForHydratedShell(page: Page): Promise<void> {
  await expect(page.locator('header').first()).toBeVisible({ timeout: 180_000 })
  await waitForHydration(page)
  await page.evaluate(async () => { await document.fonts.ready })
  const onboarding = page.getByTestId('member-permission-setup')
  if (await onboarding.isVisible()) await onboarding.getByRole('button', { name: /あとで決める|Später entscheiden/ }).click()
}

/** タブの折返しは許容し、ラベル自体の語中分断を実描画の行数で検出する。 */
export async function expectSingleLineLabel(label: Locator): Promise<void> {
  const rectangles = await label.evaluate(element => {
    const range = document.createRange()
    range.selectNodeContents(element)
    return [...range.getClientRects()].map(rect => ({ top: rect.top, height: rect.height, width: rect.width }))
  })
  expect(rectangles).toHaveLength(1)
}
