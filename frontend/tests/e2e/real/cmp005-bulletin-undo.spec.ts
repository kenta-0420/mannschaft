import { expect, test, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
const FE = process.env.BASE_URL ?? 'http://localhost:3001'
const ADMIN = {
  email: process.env.E2E_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const TEAM_ID = 1

interface BulletinMutation {
  body: string | null
  method: string
  responseArchiveFolderId: string | null
  status: number
}

async function installApiBridge(
  page: Page,
  accessToken: string,
  bulletinMutations: BulletinMutation[],
): Promise<void> {
  await page.route(/\/api\/v1\//, async (route) => {
    const request = route.request()
    const sourceUrl = new URL(request.url())
    const response = await page.request.fetch(`${BE}${sourceUrl.pathname}${sourceUrl.search}`, {
      method: request.method(),
      headers: {
        ...request.headers(),
        origin: 'http://localhost:3000',
        referer: 'http://localhost:3000/',
        authorization: `Bearer ${accessToken}`,
      },
      data: request.postData() ?? undefined,
      maxRedirects: 0,
    })
    const responseBody = await response.body()
    if (sourceUrl.pathname.includes('/bulletin/threads/') && sourceUrl.pathname.endsWith('/archive')) {
      const parsedResponse = JSON.parse(responseBody.toString()) as {
        data?: { archiveFolderId?: string | null }
      }
      bulletinMutations.push({
        body: request.postData(),
        method: request.method(),
        responseArchiveFolderId: parsedResponse.data?.archiveFolderId ?? null,
        status: response.status(),
      })
    }
    await route.fulfill({
      status: response.status(),
      headers: {
        ...response.headers(),
        'access-control-allow-origin': FE,
        'access-control-allow-credentials': 'true',
      },
      body: responseBody,
    })
  })
}

test('CMP-005: 掲示板の復元は確認なしで実行され、Undoで元フォルダへ戻る', async ({ page }) => {
  const loginResponse = await page.request.post(`${BE_API}/auth/login`, {
    data: ADMIN,
    headers: { 'Content-Type': 'application/json' },
  })
  expect(loginResponse.status(), '実BEへの管理者ログイン').toBe(200)
  const loginData = (await loginResponse.json()).data as {
    accessToken: string
    userId: number
    email: string
    fullName: string
  }
  const headers = {
    Authorization: `Bearer ${loginData.accessToken}`,
    'Content-Type': 'application/json',
  }

  const teamsResponse = await page.request.get(`${BE_API}/me/teams`, { headers })
  expect(teamsResponse.status(), '管理者の所属チーム取得').toBe(200)
  const teams = (await teamsResponse.json()).data as Array<{ id: number; slug: string }>
  const teamSlug = teams.find(team => team.id === TEAM_ID)?.slug
  expect(teamSlug, `teamId=${TEAM_ID}のslugを取得`).toBeTruthy()

  const meResponse = await page.request.get(`${BE_API}/users/me`, { headers })
  expect(meResponse.status()).toBe(200)
  const me = (await meResponse.json()).data as {
    id: number
    email: string
    lastName: string | null
    firstName: string | null
    avatarUrl: string | null
    systemRole?: string
    timezone?: string
  }

  const folderName = `CMP005 Undo ${Date.now()}`
  const folderResponse = await page.request.post(`${BE_API}/teams/${TEAM_ID}/bulletin/archive/folders`, {
    headers,
    data: { name: folderName },
  })
  expect(folderResponse.status(), '復元先フォルダ作成').toBe(201)
  const folderId = (await folderResponse.json()).data.id as string

  let threadId: number | null = null
  try {
    const threadResponse = await page.request.post(`${BE_API}/teams/${TEAM_ID}/bulletin/threads`, {
      headers,
      data: { title: `CMP005 Undo ${Date.now()}`, body: '実機Undo確認用' },
    })
    expect(threadResponse.status(), '掲示板スレッド作成').toBeLessThan(300)
    threadId = (await threadResponse.json()).data.id as number

    const archiveResponse = await page.request.post(
      `${BE_API}/teams/${TEAM_ID}/bulletin/threads/${threadId}/archive`,
      { headers, data: { isArchived: true, archiveFolderId: folderId } },
    )
    expect(archiveResponse.status(), '元フォルダへアーカイブ').toBe(200)
    const archivedThread = (await archiveResponse.json()).data as { archiveFolderId: string | null }
    expect(archivedThread.archiveFolderId, 'UI操作前の元フォルダID').toBe(folderId)

    const bulletinMutations: BulletinMutation[] = []
    await installApiBridge(page, loginData.accessToken, bulletinMutations)
    await page.addInitScript((user) => localStorage.setItem('currentUser', JSON.stringify(user)), {
      id: me.id,
      email: me.email,
      fullName: `${me.lastName ?? ''} ${me.firstName ?? ''}`.trim() || loginData.fullName,
      profileImageUrl: me.avatarUrl,
      systemRole: me.systemRole,
      timezone: me.timezone,
    })

    await page.goto(`/teams/${teamSlug}/bulletin`)
    await waitForHydration(page)
    await page.getByRole('button', { name: /保管庫/ }).click()
    await page.getByText(folderName, { exact: true }).click()

    const restoreButton = page.getByTestId(`bulletin-unarchive-${threadId}`)
    await expect(restoreButton).toBeVisible()
    await restoreButton.click()
    await expect(page.locator('.p-confirmdialog:visible')).toHaveCount(0)
    await expect.poll(async () => {
      const response = await page.request.get(`${BE_API}/teams/${TEAM_ID}/bulletin/threads?size=100`, { headers })
      const ids = ((await response.json()).data as Array<{ id: number }>).map(item => item.id)
      return ids.includes(threadId!)
    }, { message: 'UI復元後に通常一覧へ戻る', timeout: 90_000 }).toBe(true)

    const undoButton = page.getByTestId('undo-toast-button')
    await expect(undoButton).toBeVisible()
    await undoButton.click()
    await expect.poll(() => bulletinMutations.length, {
      message: '復元とUndoの2回の状態変更リクエストが実BEへ送られる',
      timeout: 90_000,
    }).toBe(2)
    expect(bulletinMutations[1]).toEqual({
      body: JSON.stringify({ isArchived: true, archiveFolderId: folderId }),
      method: 'POST',
      responseArchiveFolderId: folderId,
      status: 200,
    })
    await expect.poll(async () => {
      const response = await page.request.get(
        `${BE_API}/teams/${TEAM_ID}/bulletin/archive/threads?folder_id=${folderId}&size=100`,
        { headers },
      )
      const items = (await response.json()).data as Array<{ id: number; archiveFolderId: string | null }>
      return items.find(item => item.id === threadId)?.archiveFolderId ?? 'NOT_ARCHIVED'
    }, { message: 'Undo後のarchiveFolderIdが元のUUIDと一致する', timeout: 90_000 }).toBe(folderId)
  }
  finally {
    await page.unrouteAll({ behavior: 'ignoreErrors' })
    if (threadId != null) {
      try {
        await page.request.post(`${BE_API}/teams/${TEAM_ID}/bulletin/threads/${threadId}/archive`, {
          headers,
          data: { isArchived: false },
        })
        await page.request.delete(`${BE_API}/teams/${TEAM_ID}/bulletin/threads/${threadId}`, { headers })
      }
      catch (error) {
        console.warn('CMP-005掲示板E2Eのスレッド後片付けに失敗', error)
      }
    }
    try {
      await page.request.delete(`${BE_API}/teams/${TEAM_ID}/bulletin/archive/folders/${folderId}`, { headers })
    }
    catch (error) {
      console.warn('CMP-005掲示板E2Eのフォルダ後片付けに失敗', error)
    }
  }
})
