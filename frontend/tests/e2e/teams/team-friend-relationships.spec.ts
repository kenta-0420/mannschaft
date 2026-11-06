import { test, expect } from '@playwright/test'
import { installFriendContractFixture } from './friend-contract-fixture'

/**
 * CMP-261014-2035 E1–E7: 実在するUI操作のモック通信契約。
 * API存在/認可はBEのTeamFriends・FriendFolder・FriendFeed・FriendContentForward・
 * FriendNotification各ControllerTestが担う。cursor/metaと転送取消は
 * app/composables/friendFeedForwardContract.spec.tsで検証する（UIは新設しない）。
 */
let fixture: Awaited<ReturnType<typeof installFriendContractFixture>>
test.beforeEach(async ({ page }) => { fixture = await installFriendContractFixture(page) })
test.afterEach(() => { expect(fixture.unexpected).toEqual([]) })

const nav = '/teams/1/nav'
const friendsUrl = '/api/v1/teams/1/friends'

test('E2/E3/E7 navから現行の型でフレンド一覧を表示する', async ({ page }) => {
  await page.goto(nav)
  await expect(page.getByTestId('friend-toolbar')).toBeVisible()
  await expect(page.getByText('Rival FC', { exact: true })).toBeVisible()
  expect(fixture.calls).toContainEqual({ method: 'GET', path: friendsUrl, query: '?page=0&size=20', body: null })
})

test('E5 フレンド0件は必ず空状態を表示する', async ({ page }) => {
  fixture.friends = []
  await page.goto(nav)
  await expect(page.getByTestId('friend-list-empty')).toBeVisible()
  await expect(page.getByText('フレンドチームがありません', { exact: true })).toBeVisible()
})

test('E4 フォローPOSTのslug bodyと一覧再取得を確認する', async ({ page }) => {
  fixture.friends = []
  await page.goto(nav)
  await expect(page.getByTestId('friend-follow-button')).toBeVisible()
  await page.getByTestId('friend-follow-button').click()
  await expect(page.getByTestId('friend-follow-slug-input')).toBeVisible()
  await page.getByTestId('friend-follow-slug-input').fill('rival-fc')
  await page.getByTestId('friend-follow-submit').click()
  await expect(page.getByText('Rival FC', { exact: true })).toBeVisible()
  expect(fixture.calls.filter(call => call.method === 'POST')).toEqual([{ method: 'POST', path: friendsUrl + '/follow', query: '', body: { targetTeamSlug: 'rival-fc' } }])
  expect(fixture.calls.filter(call => call.path === friendsUrl && call.method === 'GET')).toHaveLength(2)
})

test('E4 解除DELETEのKEEP bodyと一覧更新を確認する', async ({ page }) => {
  await page.goto(nav)
  const menu = page.getByRole('button', { name: '公開設定を切替', exact: true })
  await expect(menu).toBeVisible()
  await menu.click()
  await page.getByRole('menuitem', { name: 'フォロー解除' }).click()
  const accept = page.locator('.p-confirmdialog-accept-button')
  await expect(accept).toBeVisible()
  await accept.click()
  await expect(page.getByTestId('friend-list-empty')).toBeVisible()
  expect(fixture.calls.filter(call => call.method === 'DELETE')).toEqual([{ method: 'DELETE', path: friendsUrl + '/follow/2', query: '', body: { pastForwardHandling: 'KEEP' } }])
})

test('E4 フォルダ作成POSTの完全なbodyとカード追加を確認する', async ({ page }) => {
  await page.goto('/teams/1/friend-folders')
  await expect(page.getByRole('button', { name: 'フォルダ作成', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'フォルダ作成', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByPlaceholder('例: 系列校').fill('New folder')
  await dialog.getByRole('button', { name: '作成', exact: true }).click()
  await expect(page.getByText('New folder', { exact: true })).toBeVisible()
  expect(fixture.calls.filter(call => call.method === 'POST')).toEqual([{ method: 'POST', path: '/api/v1/teams/1/friend-folders', query: '', body: { name: 'New folder', color: '#6B7280' } }])
})

test('E4 フォルダ削除DELETEとカード消失を確認する', async ({ page }) => {
  await page.goto('/teams/1/friend-folders')
  await expect(page.getByText('Training partners', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '編集', exact: true }).click()
  await page.getByRole('menuitem', { name: '削除', exact: true }).click()
  const accept = page.locator('.p-confirmdialog-accept-button')
  await expect(accept).toBeVisible()
  await accept.click()
  await expect(page.getByText('Training partners', { exact: true })).toHaveCount(0)
  await expect(page.getByText('フォルダがありません', { exact: true })).toBeVisible()
  expect(fixture.calls.filter(call => call.method === 'DELETE')).toEqual([{ method: 'DELETE', path: '/api/v1/teams/1/friend-folders/1', query: '', body: null }])
})

for (const conflict of [false, true]) {
  test('E4 転送' + (conflict ? '409は専用警告と再取得' : '成功はカードを転送済みに更新'), async ({ page }) => {
    fixture.conflict = conflict
    await page.goto('/teams/1/friend-feed')
    const forward = page.getByRole('button', { name: '自チームに転送', exact: true })
    await expect(forward).toBeVisible()
    await forward.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await dialog.locator('#forward-comment').fill('Approved')
    await dialog.getByRole('button', { name: '転送する', exact: true }).click()
    await expect(page.getByText('転送済み', { exact: true })).toBeVisible()
    expect(fixture.calls.filter(call => call.method === 'POST')).toEqual([{ method: 'POST', path: '/api/v1/teams/1/friend-feed/10/forward', query: '', body: { target: 'MEMBER', comment: 'Approved' } }])
    if (conflict) {
      await expect(page.getByText('この投稿は既に転送済みです。一覧を更新しました', { exact: true })).toBeVisible()
      expect(fixture.calls.filter(call => call.path === '/api/v1/teams/1/friend-feed' && call.method === 'GET')).toHaveLength(2)
    }
    else expect(fixture.calls.filter(call => call.path === '/api/v1/teams/1/friend-feed' && call.method === 'GET')).toHaveLength(1)
  })
}

test('E5 フィード0件は必ず空状態を表示する', async ({ page }) => {
  fixture.feed = []
  await page.goto('/teams/1/friend-feed')
  await expect(page.getByText('フレンドからの投稿はありません', { exact: true })).toBeVisible()
})

test('E3 逆転送履歴はdataとpaginationの現行型で表示する', async ({ page }) => {
  await page.goto('/teams/1/friend-forward-exports')
  await expect(page.getByRole('heading', { name: '逆転送履歴', exact: true })).toBeVisible()
  await expect(page.getByText('Rival FC', { exact: true })).toBeVisible()
  expect(fixture.calls).toContainEqual({ method: 'GET', path: '/api/v1/teams/1/friend-forward-exports', query: '?page=0&size=20', body: null })
})
