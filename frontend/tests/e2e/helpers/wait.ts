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

/**
 * ローディングスピナー(`.pi-spin`)が detached になるまで待つ。
 * ハイドレーション完了後もデータ取得中はスピナーが表示され続けるため、
 * 「データ取得完了」の中間シグナルとして各テストの前提を安定させる。
 *
 * `.catch(() => {})` で握りつぶしているのは、そもそもスピナーが最初から
 * 存在しない（データ取得が一瞬で終わる／該当ページにスピナーが無い）ケースが
 * あり得るため。「スピナーが無いこと」自体はテストの失敗要因ではなく、
 * 単に待つべき対象が無かっただけなので握りつぶして良い例外とする
 * （プロジェクト規約上のエラー握りつぶし原則禁止の例外）。
 */
export async function waitForSpinnerGone(page: Page, timeout = 20_000): Promise<void> {
  // eslint-disable-next-line no-restricted-syntax -- スピナーが最初から存在しないケース（データ取得が一瞬で終わる／該当ページにスピナーが無い）を許容するための例外。上記コメント参照。
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout }).catch(() => {})
}
