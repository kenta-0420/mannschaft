import { expect, test, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const ownerCredentials = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const outsiderCredentials = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}

interface Team {
  slug: string
}

async function createPost(
  request: APIRequestContext,
  teamSlug: string,
  content: string,
): Promise<number> {
  const response = await request.post(API + '/api/v1/timeline/posts', {
    data: { content, scopeType: 'TEAM', scopeId: teamSlug },
  })
  expect(response.status(), '使い捨て投稿作成: ' + (await response.text())).toBe(201)
  return ((await response.json()) as { data: { id: number } }).data.id
}

test('CMP-261023-0051: フィードだけ更新して新着を表示し、草稿・画面状態と越境拒否を維持する', async ({
  browser,
}, testInfo) => {
  test.setTimeout(360_000)
  const owner = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const outsider = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const anonymous = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const ownerPage = await owner.newPage()
  const outsiderPage = await outsider.newPage()
  const anonymousPage = await anonymous.newPage()
  const marker = 'TL-REFRESH-' + Date.now() + '-' + crypto.randomUUID().slice(0, 8)
  const createdPosts: number[] = []
  const cleanupErrors: string[] = []
  let team: Team | null = null
  let inviteId: number | null = null

  try {
    await loginViaApi(ownerPage, ownerCredentials, { apiBaseUrl: API })
    await loginViaApi(outsiderPage, outsiderCredentials, { apiBaseUrl: API })
    console.log('[TL-REAL] 認証完了（所有者・別アカウント）')
    const createdTeam = await owner.request.post(API + '/api/v1/teams', {
      data: { name: marker, visibility: 'MEMBERS_AND_ABOVE' },
    })
    expect(createdTeam.status(), '使い捨てチーム作成: ' + (await createdTeam.text())).toBe(201)
    team = ((await createdTeam.json()) as { data: Team }).data
    console.log('[TL-REAL] 使い捨てTEAM作成完了')

    await ownerPage.goto('/teams/' + team.slug + '/timeline', { waitUntil: 'domcontentloaded' })
    await waitForHydration(ownerPage)
    const feed = ownerPage.getByTestId('timeline-feed')
    await expect(feed).toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
    const initialPermissions = ownerPage.getByTestId('member-permission-setup')
    // システム管理者には表示されない、通常作成者向けの任意案内だけ閉じる。
    if (await initialPermissions.isVisible()) {
      await initialPermissions.getByRole('button', { name: 'あとで決める' }).click()
      await expect(initialPermissions).toBeHidden()
    }
    await expect(feed.getByTestId('team-timeline-post')).toHaveCount(0)
    await expect(feed.getByText('まだ投稿がありません', { exact: true })).toBeVisible()
    await expect(feed.getByTestId('timeline-refresh'), '初期0件でも更新できる').toBeVisible()
    console.log('[TL-REAL] 初期0件フィード・更新ボタン確認完了')
    const composer = ownerPage.getByTestId('team-timeline-composer')
    await composer.fill(marker + '-draft')

    // API は前提投稿作成のみ。対象のフィード更新は必ず画面のボタンから実行する。
    createdPosts.push(await createPost(owner.request, team.slug, marker + '-new'))
    await expect(feed.getByText(marker + '-new', { exact: true })).toHaveCount(0)
    const originalUrl = ownerPage.url()
    await ownerPage.evaluate((value) => {
      document.documentElement.dataset.timelineRefreshMarker = value
    }, marker)
    const documentRequests: string[] = []
    ownerPage.on('request', (request) => {
      if (request.resourceType() === 'document') documentRequests.push(request.url())
    })
    await ownerPage.setViewportSize({ width: 390, height: 844 })
    const refresh = feed.getByRole('button', { name: '更新', exact: true })
    const box = await refresh.boundingBox()
    expect(box?.width).toBeGreaterThanOrEqual(44)
    expect(box?.height).toBeGreaterThanOrEqual(44)
    const refreshed = ownerPage.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/timeline/feed' &&
        url.searchParams.get('scopeType') === 'TEAM' &&
        !url.searchParams.has('cursor') &&
        response.request().method() === 'GET'
      )
    })
    const editPermissionsResponse = ownerPage.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        /^\/api\/v1\/teams\/[^/]+\/me\/permissions$/.test(url.pathname) &&
        response.request().method() === 'GET'
      )
    })
    await refresh.click()
    expect((await refreshed).status()).toBe(200)
    await expect(feed.getByText(marker + '-new', { exact: true })).toBeVisible()
    const editPermissions = await editPermissionsResponse
    expect(editPermissions.status(), '新着本人投稿の編集権限取得').toBe(200)
    expect(new URL(editPermissions.url()).pathname, '編集権限APIは数値IDではなくslugを使う').toBe(
      '/api/v1/teams/' + team.slug + '/me/permissions',
    )
    console.log('[TL-REAL] 更新feed200・本人編集権限200/slug確認完了')
    await expect(composer).toHaveValue(marker + '-draft')
    expect(ownerPage.url()).toBe(originalUrl)
    expect(
      await ownerPage.evaluate(() => document.documentElement.dataset.timelineRefreshMarker),
    ).toBe(marker)
    expect(documentRequests).toEqual([])
    expect(
      await ownerPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true)
    await ownerPage.screenshot({
      path: testInfo.outputPath('timeline-refresh-mobile.png'),
      fullPage: true,
    })
    await ownerPage.setViewportSize({ width: 1440, height: 1000 })
    await ownerPage.screenshot({
      path: testInfo.outputPath('timeline-refresh-desktop.png'),
      fullPage: true,
    })

    // 他テナントと未ログインは、フィードAPI・画面・投稿URLのいずれからも読めない。
    for (const [context, page, statuses] of [
      [outsider, outsiderPage, [403, 404]],
      [anonymous, anonymousPage, [401]],
    ] as const) {
      const denied = await context.request.get(
        API + '/api/v1/timeline/feed?scopeType=TEAM&scopeId=' + team.slug,
      )
      expect(statuses as readonly number[]).toContain(denied.status())
      await page.goto('/teams/' + team.slug + '/timeline', { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      await expect(page.getByText(marker + '-new', { exact: true })).toHaveCount(0)
      await expect(page.getByTestId('timeline-refresh')).toHaveCount(0)
      if (context === anonymous) await expect(page.locator('input#email')).toBeVisible()
      else
        await expect(
          page.getByText(/^(情報を取得できませんでした|Could not load information)$/),
        ).toBeVisible()
      await page.goto('/timeline/' + createdPosts[0], { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      await expect(page.getByText(marker + '-new', { exact: true })).toHaveCount(0)
      if (context === anonymous) await expect(page.locator('input#email')).toBeVisible()
      else
        await expect(page.getByTestId('timeline-post-not-found')).toHaveText(
          /^(投稿が見つからないか、表示できません|This post was not found or cannot be displayed)$/,
        )
      console.log(
        '[TL-REAL] ' +
          (context === anonymous ? '匿名' : '別アカウント') +
          'の一覧・詳細拒否確認完了',
      )
    }

    // 越境拒否を確認したアカウントを今回のTEAMだけに招待し、通常MEMBERでも同じ更新操作を検証する。
    const invite = await owner.request.post(API + '/api/v1/teams/' + team.slug + '/invite-tokens', {
      data: { roleId: Number(process.env.E2E_MEMBER_ROLE_ID ?? '4'), maxUses: 1, expiresIn: '1d' },
    })
    expect(invite.status(), '今回のTEAM専用MEMBER招待').toBe(201)
    const invitation = ((await invite.json()) as { data: { id: number; token: string } }).data
    inviteId = invitation.id
    const joined = await outsider.request.post(API + '/api/v1/invite/' + invitation.token + '/join')
    expect(joined.status(), '正規MEMBER参加').toBe(200)
    const permissions = await outsider.request.get(
      API + '/api/v1/teams/' + team.slug + '/me/permissions',
    )
    expect(permissions.status()).toBe(200)
    expect((await permissions.json()).data.roleName).toBe('MEMBER')
    await outsiderPage.goto('/teams/' + team.slug + '/timeline', { waitUntil: 'domcontentloaded' })
    await waitForHydration(outsiderPage)
    const memberFeed = outsiderPage.getByTestId('timeline-feed')
    await expect(memberFeed.getByText(marker + '-new', { exact: true })).toBeVisible({
      timeout: 60_000,
    })
    createdPosts.push(await createPost(owner.request, team.slug, marker + '-member-new'))
    await expect(memberFeed.getByText(marker + '-member-new', { exact: true })).toHaveCount(0)
    const memberResponse = outsiderPage.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/timeline/feed' &&
        url.searchParams.get('scopeId') === team!.slug &&
        !url.searchParams.has('cursor') &&
        response.request().method() === 'GET'
      )
    })
    await memberFeed.getByTestId('timeline-refresh').click()
    expect((await memberResponse).status(), 'MEMBERの画面更新が実BEで成功').toBe(200)
    await expect(memberFeed.getByText(marker + '-member-new', { exact: true })).toBeVisible()
    console.log('[TL-REAL] MEMBERの更新feed200・別セッション新着表示確認完了')
  } finally {
    for (const id of createdPosts.reverse()) {
      try {
        const response = await owner.request.delete(API + '/api/v1/timeline/posts/' + id)
        if (response.status() !== 204)
          cleanupErrors.push('投稿 ' + id + ': HTTP ' + response.status())
      } catch (error) {
        cleanupErrors.push('投稿 ' + id + ': ' + String(error))
      }
    }
    if (team) {
      if (inviteId != null) {
        try {
          const inviteDelete = await owner.request.delete(
            API + '/api/v1/teams/' + team.slug + '/invite-tokens/' + inviteId,
          )
          if (inviteDelete.status() !== 204)
            cleanupErrors.push('招待削除: HTTP ' + inviteDelete.status())
        } catch (error) {
          cleanupErrors.push('招待削除: ' + String(error))
        }
      }
      try {
        const response = await owner.request.delete(API + '/api/v1/teams/' + team.slug)
        if (response.status() !== 204) cleanupErrors.push('チーム削除: HTTP ' + response.status())
      } catch (error) {
        cleanupErrors.push('チーム削除: ' + String(error))
      }
    }
    await Promise.all([owner.close(), outsider.close(), anonymous.close()])
    expect(cleanupErrors, '今回作成したfixtureの後片付け').toEqual([])
    console.log('[TL-REAL] 所有fixture後始末・context終了完了')
  }
})
