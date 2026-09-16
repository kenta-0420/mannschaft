import type { Page } from '@playwright/test'

/** global feature-gate を通過させるための公開フィーチャーフラグモック。 */
export async function mockFeatureFlags(page: Page): Promise<void> {
  await page.route('**/api/v1/feature-flags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [{ flagKey: 'FEATURE_SHIFT_ENABLED', enabled: true }] }),
    })
  })
}
