/**
 * F17.1 村機能 — 村参加申請ページ 実機 E2E（VLG-JR-001）。
 *
 * このテストは API モックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (BASE_URL / 既定 http://localhost:3000)
 * が起動済みの状態で実行してください。
 *
 * 背景:
 *   `pages/villages/[id].vue` の onRequestJoin が `/villages/{id}/join-request` へ遷移するが
 *   当該ページが存在せず 404 になっていた。本 spec はその導線が生きていることを固定する。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（認証情報は spec に直書きしない）。
 *
 * # 単一セッション設計（重要）
 *  実機 E2E で test を分割すると、各 test が storageState から新 context を作る一方、
 *  先行 test がリフレッシュトークンをローテートするため、後続 test の storageState が
 *  失効してログイン画面へ落ちる（実際に発生）。そのため検証は 1 test 内に集約し、
 *  test.step で区切る。
 *
 * # スコープ（意図的に seed 実態に合わせている）
 *  seed-e2e-data.js が作る村は全て joinPolicy=FREE のため、本 spec は FREE 村で確定する
 *  挙動（導線・PageHeader・使い方モーダル・「自由参加」案内）だけを検証する。
 *  APPROVAL 村でのみ出る申請フォーム / 審査一覧は、条件付き skip を入れると
 *  「テストが丸ごと skip されたのに exit 0 で緑に見える」偽陽性になるため入れていない
 *  （実際に踏んだ）。それらは APPROVAL 村を作る seed が入った時点で追加すること。
 *  なお実装時には、DB の join_policy を一時的に APPROVAL へ倒して
 *  申請フォーム→送信→取下げ（実 DB で PENDING→WITHDRAWN を確認）と審査一覧を実機検証済み。
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/** e2e-user が HEADMAN を務める村（seed-e2e-data.js の F17 ブロック / joinPolicy=FREE）。 */
const VILLAGE_ID = '6e87b493-512a-11f1-95e3-2ec96fe3ea06'

test('VLG-JR-001: 村参加申請ページの導線と使い方モーダル（単一セッション）', async ({ page }) => {
  await test.step('404 にならずページが描画される（本 PR の主目的）', async () => {
    const res = await page.goto(`/villages/${VILLAGE_ID}/join-request`)
    // Nuxt の SPA ルートなのでドキュメント自体は 200。404 ページへ落ちていないことを見る。
    expect(res?.status()).toBeLessThan(400)
    await waitForHydration(page)

    // 本ページの PageHeader（親シェルの VillageHeader とは別に描画される）
    await expect(page.getByRole('heading', { name: '参加申請', exact: true })).toBeVisible()
  })

  await test.step('使い方モーダルが開いて閉じる', async () => {
    await page.getByTestId('page-header-help').click()
    const guide = page.getByTestId('village-join-request-guide-modal')
    await expect(guide).toBeVisible()
    // ガイドのカード見出し
    await expect(page.getByRole('heading', { name: '参加を申請する' })).toBeVisible()
    // Dialog には閉じる X（headericon）と footer の「閉じる」が両方あるため footer 側に限定する
    await guide.getByRole('button', { name: '閉じる' }).last().click()
    await expect(guide).toBeHidden()
  })

  await test.step('FREE 村では「自由参加」案内が出る（BE の VILLAGE_041 と整合）', async () => {
    await expect(
      page.getByText('この村は自由参加です。参加申請ではなく直接参加してください'),
    ).toBeVisible()
    // FREE 村では申請フォームも審査一覧も出さない
    await expect(page.getByTestId('join-request-message')).toBeHidden()
    await expect(page.getByTestId('join-request-review-table')).toBeHidden()
  })
})
