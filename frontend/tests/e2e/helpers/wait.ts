import type { Page } from '@playwright/test'

/**
 * Nuxt SSR ページで Vue のクライアントサイドハイドレーション完了を待つ。
 * ハイドレーション完了前にフォーム操作すると @submit.prevent などが未バインドで
 * ネイティブフォーム送信が発生するため、フォームを操作する前に必ず呼び出す。
 *
 * Vite dev サーバーの "Outdated Optimize Dep" 504 エラーで Vue マウントが止まる場合がある。
 * 15 秒以内に成功しなければページをリロードして再試行する（1 回のリロードで
 * Vite の再最適化が完了し、2 回目の試行で成功する）。
 */
export async function waitForHydration(page: Page): Promise<void> {
  const check = (timeout: number) =>
    page.waitForFunction(
      () => {
        const el = document.querySelector('#__nuxt')
        return el !== null && '__vue_app__' in el
      },
      undefined,
      { timeout },
    )

  try {
    await check(15_000)
  } catch {
    await page.reload({ waitUntil: 'domcontentloaded' })
    await check(60_000)
  }
}
