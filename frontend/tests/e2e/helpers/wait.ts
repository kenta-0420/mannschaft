import type { Page } from '@playwright/test'

/**
 * Nuxt SSR ページで Vue のクライアントサイドハイドレーション完了を待つ。
 * ハイドレーション完了前にフォーム操作すると @submit.prevent などが未バインドで
 * ネイティブフォーム送信が発生するため、フォームを操作する前に必ず呼び出す。
 */
export async function waitForHydration(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el
  })
}
