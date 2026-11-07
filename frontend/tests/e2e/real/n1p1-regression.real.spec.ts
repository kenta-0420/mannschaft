import { test, type Browser, type Page } from '@playwright/test'
import { readWorld, type World } from './helpers/n1p1-global-setup'
import { API, expect, FE, gotoAndHydrate, openAs, responseOf, waitApi, type Actor } from './helpers/n1p1-world'

/**
 * 実機E2E: CMP-261103-1225 Phase1（BE の N+1 を一括取得へ置き換えた5PR）の表示回帰確認。
 *   #4277 今日の時間割・村フィード / #4278 権利判定・支払要件 / #4280 TODO プロジェクト一覧
 *   #4288 ダッシュボード（未読・直近予定・スコープウィジェット・業務アラート）・チャット
 *   #4294 組織メンバー一覧・チーム検索
 *
 * 置き換えは SQL だけで、画面の表示内容・見える範囲は修正前と同じでなければならない。
 * 各画面で「2件以上が正しく出る」「0件で空表示になり壊れない」を確かめ、ロール横断
 * （組織 ADMIN／同組織 MEMBER／他組織利用者）で見える範囲と URL 直打ちを確かめる。
 *
 * モックなし（page.route 不使用）。API はログイン・前提データ作成（globalSetup）・後始末（globalTeardown）だけに使う。
 * 実行: playwright-n1p1.config.ts（検証 BE 8081 / 検証 FE 3017）。
 *
 * 画面から到達できない対象（村フィードの本体ウィジェット・チャットハブ・チャットフォルダ・
 * 支払要件・ペイウォール）は、spec では扱わず結果報告に理由を記す。
 */

// 1 件の失敗で残りを止めない（default モード・workers=1）。前提データは globalSetup が1回だけ作る。
test.setTimeout(300_000)

let w: World
test.beforeAll(() => {
  w = readWorld()
})

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `test-results/n1p1/${name}.png`, fullPage: true })
}

async function withPage<T>(browser: Browser, actor: Actor, path: string | undefined, fn: (page: Page) => Promise<T>) {
  const { context, page, apiOrigins } = await openAs(browser, actor, path)
  try {
    const result = await fn(page)
    // 画面が叩いた API はすべて検証用 BE であること
    expect([...apiOrigins]).toEqual([new URL(API).origin])
    return result
  } finally {
    await context.close()
  }
}

/** 個人ダッシュボードのアコーディオン区分を開く（区分内のウィジェットは開いて初めてマウントされる）。 */
async function openSection(page: Page, key: 'schedule' | 'todo' | 'communication' | 'feed' | 'content-affiliation') {
  const button = page.locator(`#personal-dashboard-section-button-${key}`)
  await expect(button).toBeVisible({ timeout: 90_000 })
  if ((await button.getAttribute('aria-expanded')) !== 'true') await button.click()
  await expect(button).toHaveAttribute('aria-expanded', 'true')
  return page.locator(`#personal-dashboard-section-${key}`)
}

// ───────────────────────── #4277 今日の時間割 ─────────────────────────

test('#4277 今日の時間割: 未リンクのコマ2件が 500 にならず表示される（MEMBER1）', async ({ browser }) => {
  await withPage(browser, w.member1, '/dashboard', async (page) => {
    const [status] = await Promise.all([
      waitApi(page, /\/api\/v1\/me\/dashboard\/timetable-today$/, async () => {}),
      openSection(page, 'schedule'),
    ])
    expect(status).toBe(200)
    const region = page.locator('#personal-dashboard-section-schedule')
    await expect(region.getByText('今日の時間割').first()).toBeVisible({ timeout: 60_000 })
    for (const subject of w.subjects) await expect(region.getByText(subject)).toBeVisible()
    await expect(page.getByText('時間割の取得に失敗しました')).toHaveCount(0)
    await shot(page, '4277-timetable-member1')
  })
})

test('#4277 今日の時間割: 時間割の無い利用者は空表示（MEMBER2）', async ({ browser }) => {
  await withPage(browser, w.member2, '/dashboard', async (page) => {
    const [status] = await Promise.all([
      waitApi(page, /\/api\/v1\/me\/dashboard\/timetable-today$/, async () => {}),
      openSection(page, 'schedule'),
    ])
    expect(status).toBe(200)
    await expect(page.getByText('今日の時間割はありません')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('時間割の取得に失敗しました')).toHaveCount(0)
    await shot(page, '4277-timetable-member2-empty')
  })
})

// ───────────────────────── #4288 ダッシュボード ─────────────────────────

test('#4288 個人ダッシュボード: 直近の予定2件が出る（MEMBER1）／業務アラートは出ない', async ({ browser }) => {
  await withPage(browser, w.member1, undefined, async (page) => {
    const summaryCalls: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname.endsWith('/api/v1/admin/business-alerts/summary')) summaryCalls.push(r.url())
    })
    // 業務アラートの出し分けは所属チームのロール（GET /me/teams）で決まる。その応答を判定完了の証拠として待つ
    const myTeams = responseOf(page, /\/api\/v1\/me\/teams$/)
    await gotoAndHydrate(page, '/dashboard')
    const myTeamsResponse = await myTeams
    expect(myTeamsResponse.status()).toBe(200)
    const roles = ((await myTeamsResponse.json()).data as Array<{ role: string }>).map((team) => team.role)
    expect(roles.length).toBeGreaterThan(0)
    expect(roles.every((role) => role === 'MEMBER')).toBe(true)
    const [eventsStatus] = await Promise.all([
      waitApi(page, /\/api\/v1\/dashboard\/upcoming-events$/, async () => {}),
      openSection(page, 'schedule'),
    ])
    expect(eventsStatus).toBe(200)
    const schedule = page.locator('#personal-dashboard-section-schedule')
    for (const title of w.scheduleTitles) await expect(schedule.getByText(title).first()).toBeVisible({ timeout: 60_000 })
    // ロール判定（/me/teams）と画面の描画が済んだあとで、業務アラートのウィジェットも API 呼び出しも無いことを確かめる
    await expect(page.getByText('業務アラート')).toHaveCount(0)
    expect(summaryCalls).toEqual([])
    await shot(page, '4288-dashboard-member1')
  })
})

test('#4288 個人ダッシュボード: 未読（チャット）が出る（未読確認専用の READER）', async ({ browser }) => {
  // 未読はチャットを開くと既読になるため、どのテストもチャットを開かない専用の利用者・チャンネルで確かめる
  await withPage(browser, w.reader, '/dashboard', async (page) => {
    const [unread] = await Promise.all([
      responseOf(page, /\/api\/v1\/dashboard\/unread-threads$/),
      openSection(page, 'communication'),
    ])
    expect(unread.status()).toBe(200)
    // READER は専用チャンネル（ADMIN の発言2件）だけに所属し、どのテストも既読にしないので常に 2
    expect((await unread.json()).data.total_unread_chat).toBe(2)
    const communication = page.locator('#personal-dashboard-section-communication')
    // 「チャット」行（未読メッセージ）は件数が 1 以上のときだけ描画される（WidgetUnreadThreads.vue）
    const chatRow = communication.locator('div.cursor-pointer').filter({ hasText: '未読メッセージ' })
    await expect(chatRow).toBeVisible({ timeout: 30_000 })
    await expect(chatRow).toContainText('2')
    await expect(communication.getByText('未読はありません')).toHaveCount(0)
    await shot(page, '4288-dashboard-reader-unread')
  })
})

test('#4288 個人ダッシュボード: 予定0件・未読0件の利用者は空表示（他組織利用者）', async ({ browser }) => {
  await withPage(browser, w.outsider, '/dashboard', async (page) => {
    await openSection(page, 'schedule')
    // 直近予定ウィジェットの既定表示は「今週」（WidgetUpcomingEvents.vue viewMode='week'）
    await expect(page.getByText('今週の予定はありません')).toBeVisible({ timeout: 60_000 })
    for (const title of w.scheduleTitles) await expect(page.getByText(title)).toHaveCount(0)
    await openSection(page, 'communication')
    await expect(page.getByText('未読はありません')).toBeVisible({ timeout: 60_000 })
    await shot(page, '4288-dashboard-outsider-empty')
  })
})

test('#4288 業務アラート: チーム/組織 ADMIN の個人ダッシュボードに出る（件数0は空表示）', async ({ browser }) => {
  await withPage(browser, w.admin, undefined, async (page) => {
    const status = await waitApi(page, /\/api\/v1\/admin\/business-alerts\/summary$/, () => gotoAndHydrate(page, '/dashboard'))
    expect(status).toBe(200)
    await expect(page.getByText('業務アラート').first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('アラートはありません')).toBeVisible()
    await shot(page, '4288-admin-alert-admin')
  })
})

test('#4288 チームのスコープウィジェット: 今後の予定2件・チャットが出る（MEMBER1 / T1）', async ({ browser }) => {
  await withPage(browser, w.member1, '/dashboard', async (page) => {
    await page.getByTestId('scope-segment-TEAM').click()
    const chip = page.getByTestId(`scope-tab-chip-TEAM-${w.t1.slug}`)
    if (await chip.isVisible().catch(() => false)) await chip.click()
    const grid = page.getByTestId('swipe-widget-grid-TEAM')
    await expect(grid).toBeVisible({ timeout: 60_000 })
    for (const title of w.scheduleTitles) await expect(grid.getByText(title).first()).toBeVisible({ timeout: 30_000 })
    await expect(grid.getByText(w.channel.name).first()).toBeVisible()
    await shot(page, '4288-scope-widget-team-member1')
  })
})

test('#4288 チームのスコープウィジェット: 予定0件のチームは空表示（ADMIN / T2）', async ({ browser }) => {
  await withPage(browser, w.admin, '/dashboard', async (page) => {
    await page.getByTestId('scope-segment-TEAM').click()
    await page.getByTestId(`scope-tab-chip-TEAM-${w.t2.slug}`).click()
    const grid = page.getByTestId('swipe-widget-grid-TEAM')
    await expect(grid).toBeVisible({ timeout: 60_000 })
    await expect(grid.getByText('今後の予定はありません').first()).toBeVisible({ timeout: 30_000 })
    await expect(grid.getByText('チャットはありません').first()).toBeVisible()
    await shot(page, '4288-scope-widget-team-t2-empty')
  })
})

// ───────────────────────── #4288 チャット ─────────────────────────

test('#4288 チャット: 送信者名・添付が出る（MEMBER1 が一覧から開く）', async ({ browser }) => {
  await withPage(browser, w.member1, '/chat', async (page) => {
    const entry = page.getByText(w.channel.name).first()
    await expect(entry).toBeVisible({ timeout: 60_000 })
    const status = await waitApi(page, new RegExp(`/api/v1/chat/channels/${w.channel.id}/messages$`), () => entry.click())
    expect(status).toBe(200)
    const bubbles = page.getByTestId('chat-message')
    for (const text of w.messages) await expect(bubbles.filter({ hasText: text })).toHaveCount(1, { timeout: 30_000 })
    await expect(bubbles.filter({ hasText: w.messages[0]! })).toContainText(w.admin.nickname)
    await expect(bubbles.filter({ hasText: w.messages[1]! })).toContainText(w.member1.nickname)
    await expect(bubbles.filter({ hasText: w.messages[2]! }).getByText(w.attachmentName)).toBeVisible()
    await expect(page.getByText('不明', { exact: true })).toHaveCount(0)
    await shot(page, '4288-chat-messages-member1')
  })
})

test('#4288 チャット: 発言0件のチャンネルは壊れず空のまま（MEMBER1）', async ({ browser }) => {
  await withPage(browser, w.member1, undefined, async (page) => {
    // 応答待ちを先に登録してから遷移する（遷移後に登録すると速い環境で応答を取り逃す）
    const status = await waitApi(page, new RegExp(`/api/v1/chat/channels/${w.emptyChannel.id}/messages$`), () =>
      gotoAndHydrate(page, `/chat?channel=${w.emptyChannel.id}`),
    )
    expect(status).toBe(200)
    await expect(page.getByTestId('team-chat-input')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('chat-message')).toHaveCount(0)
    await shot(page, '4288-chat-empty-channel')
  })
})

test('#4288 チャット 他テナント: 他組織利用者には一覧に出ず、?channel= 直打ちでも開けない', async ({ browser }) => {
  await withPage(browser, w.outsider, undefined, async (page) => {
    // 認可判定が終わった証拠: 自分のチャンネル一覧（200）と、対象チャンネル取得の拒否（403/404）の応答を受け取ってから非表示を確かめる
    const list = responseOf(page, /\/api\/v1\/chat\/channels$/)
    const detail = responseOf(page, new RegExp(`/api/v1/chat/channels/${w.channel.id}$`))
    await gotoAndHydrate(page, `/chat?channel=${w.channel.id}`)
    expect((await list).status()).toBe(200)
    const detailStatus = (await detail).status()
    console.log(`[n1p1] 他組織 GET /chat/channels/${w.channel.id} -> ${detailStatus}`)
    expect([403, 404]).toContain(detailStatus)
    // ?channel= の自動オープンが 403/404 を受けたときの警告（locales/ja/common.json chat.tab.channelNotAccessible）
    await expect(page.getByText('指定されたチャンネルにアクセスできません').first()).toBeVisible({ timeout: 60_000 })
    for (const text of w.messages) await expect(page.getByText(text)).toHaveCount(0)
    await expect(page.getByText(w.channel.name)).toHaveCount(0)
    await shot(page, '4288-chat-outsider-direct')
  })
})

// ───────────────────────── #4280 TODO プロジェクト一覧 ─────────────────────────

test('#4280 プロジェクト一覧: 2件と件数・進捗が出る（MEMBER1 / T1）', async ({ browser }) => {
  await withPage(browser, w.member1, undefined, async (page) => {
    const status = await waitApi(page, new RegExp(`/api/v1/teams/${w.t1.slug}/projects$`), () => gotoAndHydrate(page, `/teams/${w.t1.slug}/projects`))
    expect(status).toBe(200)
    await expect(page.getByText(w.projectFull.title)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(w.projectEmpty.title)).toBeVisible()
    await expect(page.getByText('1/2 タスク')).toBeVisible()
    await expect(page.getByText('0/0 タスク')).toBeVisible()
    // 進捗率の表示（観測値を記録する。BE は 0〜100 の百分率（ProjectRepository.recalculateProgress）。FE が更に 100 倍している既存欠陥の観測。N+1 置換の対象外）
    const fullCardText = await page.getByText('1/2 タスク').locator('xpath=ancestor::*[contains(., "%")][1]').innerText()
    console.log(`[n1p1] プロジェクト「あり」カードの進捗表示: ${fullCardText.replace(/\s+/g, ' ')}`)
    await shot(page, '4280-projects-member1')
    // 詳細: マイルストーン件数と TODO 件数
    await page.getByText(w.projectFull.title).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${w.projectFull.id}`))
    await expect(page.getByText(w.milestoneTitle).first()).toBeVisible({ timeout: 60_000 })
    await shot(page, '4280-project-detail-member1')
  })
})

test('プロジェクト一覧の進捗率は TODO 1/2 完了で 50% と出る（CMP-261105-1507 で 100 倍表示を修正）', async ({ browser }) => {
  // BE は progressRate を 0〜100 の百分率で返す（ProjectRepository.recalculateProgress の `* 100.0`）。
  // 以前は FE が更に 100 倍し 5000% と出ていた（test.fail で明示していた既存欠陥）。FE は百分率をそのまま丸めて出す。
  await withPage(browser, w.member1, `/teams/${w.t1.slug}/projects`, async (page) => {
    const card = page.getByText('1/2 タスク').locator('xpath=ancestor::*[contains(., "%")][1]')
    await expect(card).toBeVisible({ timeout: 60_000 })
    await expect(card).toContainText('50%', { timeout: 5_000 })
    await expect(card).not.toContainText('5000%')
  })
})

test('#4280 プロジェクト一覧: 0件のチームは空表示（ADMIN / T2）', async ({ browser }) => {
  await withPage(browser, w.admin, `/teams/${w.t2.slug}/projects`, async (page) => {
    await expect(page.getByText('プロジェクトがありません')).toBeVisible({ timeout: 60_000 })
    await shot(page, '4280-projects-t2-empty')
  })
})

test('#4280 プロジェクト 他テナント: 他組織利用者は一覧・詳細 URL 直打ちとも中身が出ない', async ({ browser }) => {
  await withPage(browser, w.outsider, undefined, async (page) => {
    // 認可判定が終わった証拠として、一覧・詳細 API の拒否応答（403/404）を受け取ってから非表示を確かめる
    const list = responseOf(page, new RegExp(`/api/v1/teams/${w.t1.slug}/projects$`))
    await gotoAndHydrate(page, `/teams/${w.t1.slug}/projects`)
    const listStatus = (await list).status()
    console.log(`[n1p1] 他組織 GET /teams/{t1}/projects -> ${listStatus}`)
    expect([403, 404]).toContain(listStatus)
    await expect(page.getByText(w.projectFull.title)).toHaveCount(0)
    await expect(page.getByText(w.projectEmpty.title)).toHaveCount(0)
    await shot(page, '4280-projects-outsider-list')
    const detail = responseOf(page, new RegExp(`/api/v1/teams/${w.t1.slug}/projects/${w.projectFull.id}$`))
    await gotoAndHydrate(page, `/teams/${w.t1.slug}/projects/${w.projectFull.id}`)
    const detailStatus = (await detail).status()
    console.log(`[n1p1] 他組織 GET /teams/{t1}/projects/{id} -> ${detailStatus}`)
    expect([403, 404]).toContain(detailStatus)
    await expect(page.getByText(w.projectFull.title)).toHaveCount(0)
    await expect(page.getByText(w.milestoneTitle)).toHaveCount(0)
    await shot(page, '4280-project-detail-outsider')
  })
})

// ───────────────────────── #4294 組織メンバー一覧 ─────────────────────────

test('#4294 組織メンバー一覧: ADMIN に3人（ロール付き）が出る', async ({ browser }) => {
  await withPage(browser, w.admin, undefined, async (page) => {
    const status = await waitApi(page, new RegExp(`/api/v1/organizations/${w.orgA.slug}/members$`), () =>
      gotoAndHydrate(page, `/organizations/${w.orgA.slug}/members`),
    )
    expect(status).toBe(200)
    for (const actor of [w.admin, w.member1, w.member2]) await expect(page.getByText(actor.nickname).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(w.outsider.nickname)).toHaveCount(0)
    await expect(page.getByTestId(`member-remove-${w.member1.id}`)).toBeVisible()
    await shot(page, '4294-org-members-admin')
  })
})

test('#4294 組織メンバー一覧: 同組織 MEMBER では管理操作が出ない', async ({ browser }) => {
  await withPage(browser, w.member1, undefined, async (page) => {
    // 一覧の取得（200）と自分のロール（me/permissions）の応答を受け、一覧が描画されてから管理操作の非表示を確かめる
    const members = responseOf(page, new RegExp(`/api/v1/organizations/${w.orgA.slug}/members$`))
    const permissions = responseOf(page, /\/api\/v1\/organizations\/[^/]+\/me\/permissions$/)
    await gotoAndHydrate(page, `/organizations/${w.orgA.slug}/members`)
    expect((await members).status()).toBe(200)
    const permissionsResponse = await permissions
    expect(permissionsResponse.status()).toBe(200)
    console.log(`[n1p1] MEMBER1 組織 me/permissions roleName=${(await permissionsResponse.json()).data?.roleName}`)
    for (const actor of [w.admin, w.member1, w.member2]) await expect(page.getByText(actor.nickname).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId(`member-remove-${w.member2.id}`)).toHaveCount(0)
    await expect(page.getByTestId(`member-permission-groups-${w.member2.id}`)).toHaveCount(0)
    await shot(page, '4294-org-members-member1')
  })
})

test('#4294 組織メンバー一覧 他テナント: 他組織利用者の URL 直打ちでメンバーが出ない', async ({ browser }) => {
  await withPage(browser, w.outsider, undefined, async (page) => {
    // 認可判定が終わった証拠として、メンバー一覧 API の拒否応答（403/404）を受け取ってから非表示を確かめる
    const members = responseOf(page, new RegExp(`/api/v1/organizations/${w.orgA.slug}/members$`))
    await gotoAndHydrate(page, `/organizations/${w.orgA.slug}/members`)
    const status = (await members).status()
    console.log(`[n1p1] 他組織 GET /organizations/{orgA}/members -> ${status}`)
    expect([403, 404]).toContain(status)
    for (const actor of [w.admin, w.member1, w.member2]) await expect(page.getByText(actor.nickname)).toHaveCount(0)
    await shot(page, '4294-org-members-outsider')
  })
})

test('#4294 組織メンバー一覧: メンバー1人だけの組織 B（自分のみ）が壊れず出る', async ({ browser }) => {
  await withPage(browser, w.outsider, `/organizations/${w.orgB.slug}/members`, async (page) => {
    await expect(page.getByText(w.outsider.nickname).first()).toBeVisible({ timeout: 60_000 })
    await shot(page, '4294-org-members-orgB-single')
  })
})

// ───────────────────────── #4294 チーム検索 ─────────────────────────

test('#4294 チーム検索: キーワードで2件と人数（3人/1人）が出る／該当なしは空表示', async ({ browser }) => {
  await withPage(browser, w.member2, '/teams/search', async (page) => {
    const input = page.getByPlaceholder('チーム検索')
    await expect(input).toBeVisible({ timeout: 60_000 })
    await input.fill(`N1検証チーム`)
    await input.press('End')
    await input.fill(w.tag)
    const status = await waitApi(page, /\/api\/v1\/(teams\/search|public\/teams|discover\/teams)/, () => page.getByRole('button', { name: '検索' }).click())
    expect(status).toBe(200)
    const card1 = page.locator('div').filter({ has: page.getByRole('heading', { name: w.t1.name }) }).last()
    const card2 = page.locator('div').filter({ has: page.getByRole('heading', { name: w.t2.name }) }).last()
    await expect(page.getByRole('heading', { name: w.t1.name })).toBeVisible({ timeout: 30_000 })
    await expect(page.getByRole('heading', { name: w.t2.name })).toBeVisible()
    await expect(card1).toBeVisible()
    await expect(card2).toBeVisible()
    await shot(page, '4294-team-search-hit')
    // 人数は各カード内で確認（T1=3人, T2=1人）
    const cards = page.locator('.grid > *')
    await expect(cards.filter({ hasText: w.t1.name })).toContainText('3人')
    await expect(cards.filter({ hasText: w.t2.name })).toContainText('1人')

    await input.fill(`zz-none-${w.tag}`)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.getByText('該当するチームが見つかりませんでした')).toBeVisible({ timeout: 30_000 })
    await shot(page, '4294-team-search-empty')
  })
})

// ───────────────────────── #4288 業務アラート（スコープの管理者レンズ）とロール横断 ─────────────────────────

test('#4288 業務アラート（組織の管理者レンズ）: ADMIN は管理者レンズで業務アラートが出る', async ({ browser }) => {
  await withPage(browser, w.admin, '/dashboard', async (page) => {
    await page.getByTestId('scope-segment-ORGANIZATION').click()
    const chip = page.getByTestId(`scope-tab-chip-ORGANIZATION-${w.orgA.slug}`)
    if (await chip.isVisible().catch(() => false)) await chip.click()
    // 他パネル（チーム）にも同じ testid が非表示で描画されるため、表示中の物に絞る
    const lens = page.getByRole('group', { name: '管理者表示の切り替え' }).getByRole('button', { name: '管理者', exact: true })
    await expect(lens).toBeVisible({ timeout: 60_000 })
    const status = await waitApi(page, new RegExp(`/api/v1/dashboard/organization/${w.orgA.slug}/admin-business-alert$`), async () => {
      if ((await lens.getAttribute('aria-pressed')) !== 'true') await lens.click()
    })
    expect(status).toBe(200)
    await expect(page.locator('[data-testid="admin-alert-total-ORGANIZATION"]:visible')).toBeVisible({ timeout: 30_000 })
    await shot(page, '4288-admin-lens-org-admin')
  })
})

test('#4288 業務アラート 負の視点: 同組織 MEMBER には管理者レンズ・業務アラートが出ず、管理コンソール URL 直打ちは組織トップへ戻される', async ({ browser }) => {
  await withPage(browser, w.member1, undefined, async (page) => {
    // レンズの出し分けは組織の me/permissions（useRoleAccess）で決まる。その応答（MEMBER）を判定完了の証拠として待つ
    const permissions = responseOf(page, /\/api\/v1\/organizations\/[^/]+\/me\/permissions$/)
    await gotoAndHydrate(page, '/dashboard')
    await page.getByTestId('scope-segment-ORGANIZATION').click()
    const permissionsResponse = await permissions
    expect(permissionsResponse.status()).toBe(200)
    expect((await permissionsResponse.json()).data?.roleName).toBe('MEMBER')
    await expect(page.getByTestId('swipe-widget-grid-ORGANIZATION')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('group', { name: '管理者表示の切り替え' })).toHaveCount(0)
    await expect(page.getByTestId('admin-alert-total-ORGANIZATION')).toHaveCount(0)
    await shot(page, '4288-admin-lens-org-member1')
    // 管理コンソール（admin-console ミドルウェア）を URL 直打ち → 組織トップへ戻される
    await page.goto(`/organizations/${w.orgA.slug}/admin`)
    await expect(page).toHaveURL(new RegExp(`/organizations/${w.orgA.slug}/?$`), { timeout: 60_000 })
    await shot(page, '4288-admin-console-member1-direct')
    console.log(`[n1p1] MEMBER1 /admin 直打ち → ${page.url()}`)
  })
})

test('#4288 業務アラート 他テナント: 他組織利用者の管理コンソール URL 直打ちは管理画面を出さない', async ({ browser }) => {
  await withPage(browser, w.outsider, undefined, async (page) => {
    await page.goto(`/organizations/${w.orgA.slug}/admin`)
    // 固定待ちでは dev サーバーの水和が終わらない回があった（loading のまま）。追い返し先の URL を待つ
    await expect(page).toHaveURL(new RegExp(`/organizations/${w.orgA.slug}/?$`), { timeout: 90_000 })
    // 戻された組織トップは非メンバー向けの公開表示（locales/ja dashboard public_viewer_hint_organization）
    await expect(page.getByText('あなたはこの組織のメンバーではないため、一部のウィジェットは非表示になっています')).toBeVisible({ timeout: 90_000 })
    await expect(page.getByTestId('admin-alert-total-ORGANIZATION')).toHaveCount(0)
    await shot(page, '4288-admin-console-outsider-direct')
    console.log(`[n1p1] 他組織 /admin 直打ち → ${page.url()} 本文冒頭=${((await page.locator('main').first().innerText().catch(() => '')) || '').replace(/\s+/g, ' ').slice(0, 120)}`)
  })
})

test('環境の証拠: 画面は検証 BE を叩いている', async () => {
  console.log(`[n1p1] FE=${FE} API=${API}`)
  expect(new URL(API).port).toBe('8081')
})
