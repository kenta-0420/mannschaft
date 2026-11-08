import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import {
  RUN,
  type Actor,
  type Scope,
  affiliate,
  apiAs,
  archiveScope,
  clickAndWait,
  createActor,
  createGroup,
  createOrg,
  createTeam,
  dismissPermissionSetup,
  FANOUT_WAIT_MS,
  joinScope,
  must,
  notificationsOf,
  openAs,
  putSettings,
  sql,
  resetUserRateLimit,
  browserApiOrigins,
} from './helpers/f0121-org-groups-world'

/**
 * F01.2.1 グループ宛てお知らせ（告知ウィザード）の実機E2E（CMP-261011-0035）。
 *
 * 実行（専用設定。globalTeardown で作成データを必ず削除する。通常設定ではこの spec は除外される）:
 *   BASE_URL=http://localhost:3021 API_BASE_URL=http://localhost:8081
 *   node node_modules/playwright/cli.js test -c playwright-f0121.config.ts \
 *   tests/e2e/real/f0121-org-group-broadcast.real.spec.ts
 *
 * 前提（設計書 §16 H）: 組織X にグループ G1〜G4（この順）、各グループにチーム1つ（T1〜T4）、未分類 T0、
 * 直属メンバー XO（どのチームにも属さない MEMBER）。A1〜A4・A0 は各チームの ADMIN（ダッシュボードの閲覧者）、
 * B13 は T1 と T3 の両方の MEMBER。前提の作成・グループ割当の準備・観測（DB・通知一覧）だけ API を使い、
 * 告知の送信（ステップ1〜3）とダッシュボード・送信履歴の確認は画面で行う。
 */

interface World {
  XA: Actor
  XM: Actor
  XO: Actor
  A: Record<'1' | '2' | '3' | '4' | '0', Actor>
  B13: Actor
  X: Scope
  Z: Scope
  T: Record<'1' | '2' | '3' | '4' | '0', Scope>
  G: Record<'1' | '2' | '3' | '4', string>
  GZEmpty: string
}

const cacheDir = path.resolve('test-results')
const cacheFile = path.join(cacheDir, `f0121-broadcast-world-${process.ppid}.json`)
let w: World
const GN = { '1': `G1 ${RUN}`, '2': `G2 ${RUN}`, '3': `G3 ${RUN}`, '4': `G4 ${RUN}` }
const KEYS = ['1', '2', '3', '4', '0'] as const

test.describe.configure({ mode: 'default' })
test.use({ screenshot: 'only-on-failure', trace: 'off' })

test.beforeAll(async () => {
  test.setTimeout(900_000)
  if (existsSync(cacheFile)) {
    w = JSON.parse(readFileSync(cacheFile, 'utf8')) as World
    return
  }
  const XA = await createActor('BXA', 'bc')
  const XM = await createActor('BXM', 'bc')
  const XO = await createActor('BXO', 'bc')
  const B13 = await createActor('B13', 'bc')
  const A = {} as World['A']
  for (const k of KEYS) A[k] = await createActor(`BA${k}`, 'bc')
  const X = await createOrg(XA, `実機告知組織X ${RUN}`)
  await joinScope('organizations', XA, X.slug, XM, 'MEMBER')
  await joinScope('organizations', XA, X.slug, XO, 'MEMBER')
  await putSettings(XA, X.slug, { teamApplicationEnabled: false, teamGroupsEnabled: true, applicationGroupMode: 'OFF' })
  const G = {} as World['G']
  for (const k of ['1', '2', '3', '4'] as const) G[k] = await createGroup(XA, X.slug, GN[k])
  const T = {} as World['T']
  for (const k of KEYS) {
    T[k] = await createTeam(A[k], `実機告知T${k} ${RUN}`)
    await affiliate(XA, X.slug, A[k], T[k].slug, k === '0' ? undefined : G[k as '1'])
  }
  await joinScope('teams', A['1'], T['1'].slug, B13, 'MEMBER')
  await joinScope('teams', A['3'], T['3'].slug, B13, 'MEMBER')
  // AC-H06 用: 直属メンバーのいない組織Z（ADMIN の A1 は Z の加盟チーム T1 に所属）と空グループ
  const Z = await createOrg(A['1'], `実機告知組織Z ${RUN}`)
  await putSettings(A['1'], Z.slug, { teamApplicationEnabled: false, teamGroupsEnabled: true, applicationGroupMode: 'OFF' })
  const gz1 = await createGroup(A['1'], Z.slug, `Z1 ${RUN}`)
  const GZEmpty = await createGroup(A['1'], Z.slug, `Z空 ${RUN}`)
  await affiliate(A['1'], Z.slug, A['1'], T['1'].slug, gz1)
  w = { XA, XM, XO, A, B13, X, Z, T, G, GZEmpty }
  mkdirSync(cacheDir, { recursive: true })
  writeFileSync(cacheFile, JSON.stringify(w, null, 2))
  console.log(`[F0121-B] world X=${X.slug} Z=${Z.slug} T=${KEYS.map((k) => T[k].slug).join(',')}`)
})

function firstJob<T>(jobs: T[]): T {
  const job = jobs[0]
  if (job === undefined) throw new Error('fanout ジョブが 1 件も無い')
  return job
}

function jobsSince(orgNumericId: number, since: string): Array<{ scopeType: string; scopeRef: string; type: string }> {
  const out = sql(
    `SELECT scope_type, scope_ref, notification_type FROM notification_fanout_jobs WHERE organization_id=${orgNumericId} ` +
      `AND created_at >= '${since}' AND shard_index=0 ORDER BY created_at;`,
  )
  if (!out) return []
  return out.split('\n').map((line) => {
    const [scopeType, scopeRef, type] = line.split('\t')
    if (scopeType === undefined || scopeRef === undefined || type === undefined) {
      throw new Error(`fanout ジョブ行の列数が想定外: ${JSON.stringify(line)}`)
    }
    return { scopeType, scopeRef, type }
  })
}

function audienceTeams(scopeRef: string): number[] {
  const out = sql(
    `SELECT team_id FROM notification_fanout_audience_teams WHERE audience_snapshot_id=UUID_TO_BIN('${scopeRef}') ORDER BY team_id;`,
  )
  return out ? out.split('\n').map(Number) : []
}

function utcNow(): string {
  return sql('SELECT DATE_FORMAT(UTC_TIMESTAMP(3), "%Y-%m-%d %H:%i:%s.%f");')
}

async function openWizard(page: Page) {
  await dismissPermissionSetup(page)
  const button = page.getByRole('button', { name: '組織内告知' })
  await expect(button).toBeVisible({ timeout: 30_000 })
  await button.click()
  await expect(page.getByRole('dialog').first()).toBeVisible()
}

async function pick(page: Page, testId: string, option: string) {
  await page.getByTestId(testId).click()
  await page.getByRole('option', { name: option, exact: true }).filter({ visible: true }).first().click()
}

/** ステップ1でグループ範囲「〜以前 G2」を選び、宛先プレビューを待つ。 */
async function chooseUpTo(page: Page, groupName: string) {
  await page.getByTestId('broadcast-target-mode-groups').click()
  await pick(page, 'broadcast-range-kind', '〜以前')
  const preview = page.waitForResponse((r) => r.url().includes('/broadcast/audience-preview') && r.request().method() === 'POST')
  await pick(page, 'broadcast-range-to', groupName)
  return preview
}

async function goChannelAndContent(page: Page, channel: '掲示板' | 'アンケート', title: string) {
  await page.getByTestId('broadcast-step1-next').click()
  await page.getByRole('dialog').first().getByRole('button', { name: channel }).click()
  await page.getByTestId('broadcast-step2-next').click()
  const dialog = page.getByRole('dialog').first()
  await dialog.locator('input.p-inputtext').first().fill(title)
  if (channel === '掲示板') await dialog.locator('textarea').first().fill(`${title} の本文`)
}

async function submit(page: Page) {
  return clickAndWait(
    page,
    () => page.getByRole('dialog').first().getByRole('button', { name: '告知を送る' }).click(),
    'POST',
    new RegExp(`/api/v1/organizations/[^/]+/broadcast$`),
  )
}

/**
 * チームダッシュボード（/dashboard の「チーム」タブ。閲覧者は各チームにだけ所属する ADMIN）に、
 * 指定タイトルのお知らせが出ているかを画面で確かめる。
 */
async function teamDashboardShows(browser: import('@playwright/test').Browser, actor: Actor, team: Scope, title: string): Promise<boolean> {
  const s = await openAs(browser, actor, '/dashboard')
  try {
    const loaded = s.page.waitForResponse(
      (r) => r.url().includes(`/api/v1/dashboard/team/${team.slug}`) && r.request().method() === 'GET',
      { timeout: 60_000 },
    )
    await s.page.getByTestId('scope-segment-TEAM').click()
    expect((await loaded).status()).toBe(200)
    const panel = s.page.locator('[role="tabpanel"][aria-labelledby="scope-tab-TEAM"]')
    await expect(panel).toBeVisible({ timeout: 30_000 })
    // お知らせ欄の描画を待つ（出ない側の判定も同じ待ち時間を置く）
    await s.page.waitForTimeout(2_000)
    return (await panel.getByText(title).count()) > 0
  } finally {
    await s.context.close()
  }
}

/** 組織ダッシュボード（/dashboard の「組織」タブ）を開き、組織のお知らせ欄を含むパネルを返す。 */
async function openOrgDashboard(browser: import('@playwright/test').Browser, actor: Actor) {
  const s = await openAs(browser, actor, '/dashboard')
  const loaded = s.page.waitForResponse(
    (r) => r.url().includes(`/api/v1/dashboard/organization/${w.X.slug}`) && r.request().method() === 'GET',
    { timeout: 60_000 },
  )
  await s.page.getByTestId('scope-segment-ORGANIZATION').click()
  expect((await loaded).status()).toBe(200)
  const panel = s.page.locator('[role="tabpanel"][aria-labelledby="scope-tab-ORGANIZATION"]')
  await expect(panel).toBeVisible({ timeout: 30_000 })
  return { ...s, panel }
}

/** 送信時の宛先表示（AC-H17）。範囲「G2 以前」・送信時 2 チーム。 */
const B1_AUDIENCE = () => `宛先: ${GN['2']} 以前（並び順で先頭から）（送信時 2 チーム）`

/**
 * 送信履歴（組織のお知らせ一覧）で、告知 B1 の項目に限って宛先表示を検証する。
 * 改名後の名前が出ないこと（送信時の名前のまま）も確かめる。
 */
async function expectHistoryAudience(browser: import('@playwright/test').Browser, when: string) {
  const history = await openOrgDashboard(browser, w.XA)
  try {
    const item = history.panel.locator('[data-announcement-item]').filter({ hasText: B1 })
    await expect(item, `${when}: 送信履歴に B1 の項目がある`).toHaveCount(1, { timeout: 30_000 })
    const summary = item.getByTestId('broadcast-audience-summary')
    await expect(summary, `${when}: B1 の宛先表示（宛先名・範囲・送信時のチーム数）`).toHaveText(B1_AUDIENCE(), { timeout: 10_000 })
    await expect(item, `${when}: 改名後の名前は出ない`).not.toContainText(`G2改 ${RUN}`)
  } finally {
    await history.context.close()
  }
}

const B1 = `G2以前の掲示板 ${RUN}`
const S1 = `G2以前のアンケート ${RUN}`

test.describe('F01.2.1 グループ宛てお知らせの実機E2E', () => {
  test('BC-01 [AC-L05/H15/H01/H17/H19/H26] XA が組織ダッシュボードの「組織内告知」から G2 以前の掲示板を送り、完了パネルに宛先が出る', async ({ browser }) => {
    test.setTimeout(600_000)
    const since = utcNow()
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const page = s.page
    try {
      await openWizard(page)
      const preview = await chooseUpTo(page, GN['2'])
      const body = (await preview.json()).data
      // AC-H15: G2 以前 → 2 チーム、T1・T2 の名前が返る
      expect(body.resolvedTeamCount).toBe(2)
      expect(JSON.stringify(body)).toContain(w.T['1'].name)
      expect(JSON.stringify(body)).toContain(w.T['2'].name)
      await expect(page.getByTestId('broadcast-resolved-count')).toContainText(`該当: 2 チーム＋直属メンバー ${body.directMemberCount} 人`)
      await goChannelAndContent(page, '掲示板', B1)
      const sent = await submit(page)
      expect(sent.status(), await sent.text()).toBeLessThan(300)
      // 送信後の完了パネル（宛先: G2 以前…（送信時 2 チーム））
      const panel = page.getByTestId('broadcast-sent-panel')
      await expect(panel).toBeVisible({ timeout: 30_000 })
      await expect(panel.getByTestId('broadcast-audience-summary')).toHaveText(B1_AUDIENCE())
      await page.getByTestId('broadcast-sent-close').click()
      await expect(panel).toBeHidden()
    } finally {
      await s.context.close()
    }
    // AC-H26: スナップショット行（G1-T1, G2-T2）。AC-H19: 掲示板は fan-out ジョブを作らない
    const snapshot = sql(
      `SELECT s.group_id, s.team_id FROM announcement_feed_group_snapshots s JOIN announcement_feeds f ON f.id=s.feed_id ` +
        `WHERE f.title_cache='${B1}' ORDER BY s.team_id;`,
    )
    expect(snapshot.split('\n').map((l) => Number(l.split('\t')[1]))).toEqual([w.T['1'].numericId, w.T['2'].numericId].sort((a, b) => a - b))
    await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
    expect(jobsSince(w.X.numericId, since)).toEqual([])
  })

  test('BC-02 [AC-H01/H16] G2 以前の掲示板は T1・T2 のダッシュボードに出て、T3・T4・T0 には出ない。XO は組織で見られる', async ({ browser }) => {
    test.setTimeout(800_000)
    const shown: Record<string, boolean> = {}
    for (const k of KEYS) shown[k] = await teamDashboardShows(browser, w.A[k], w.T[k], B1)
    expect(shown).toEqual({ '1': true, '2': true, '3': false, '4': false, '0': false })
    const xo = await openOrgDashboard(browser, w.XO)
    try {
      await expect(xo.panel.getByText(B1).first()).toBeVisible({ timeout: 30_000 })
    } finally {
      await xo.context.close()
    }
  })

  test('BC-03 [AC-H07/H08/H17] G2 以前のアンケートは ORGANIZATION_TEAMS ジョブ1件（宛先 T1・T2）で、T1・T2 と XO・XA に push が届き T3・T4・T0 には届かない', async ({ browser }) => {
    test.setTimeout(600_000)
    const since = utcNow()
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    try {
      await openWizard(s.page)
      await chooseUpTo(s.page, GN['2'])
      await expect(s.page.getByTestId('broadcast-resolved-count')).toContainText('該当: 2 チーム')
      await goChannelAndContent(s.page, 'アンケート', S1)
      const sent = await submit(s.page)
      expect(sent.status(), await sent.text()).toBeLessThan(300)
      await expect(s.page.getByTestId('broadcast-sent-panel')).toContainText('（送信時 2 チーム）', { timeout: 30_000 })
    } finally {
      await s.context.close()
    }
    await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
    let jobs: ReturnType<typeof jobsSince> = []
    await expect.poll(() => (jobs = jobsSince(w.X.numericId, since)).length, { timeout: 60_000 }).toBeGreaterThan(0)
    expect(jobs.map((j) => j.scopeType)).toEqual(['ORGANIZATION_TEAMS'])
    expect(audienceTeams(firstJob(jobs).scopeRef)).toEqual([w.T['1'].numericId, w.T['2'].numericId].sort((a, b) => a - b))
    const received = async (actor: Actor) =>
      (await notificationsOf(actor)).filter((n) => `${n.title ?? ''}${(n as { body?: string }).body ?? ''}`.includes(S1)).length
    await expect.poll(() => received(w.A['1']), { timeout: 60_000 }).toBe(1)
    expect(await received(w.A['2'])).toBe(1)
    expect(await received(w.XO)).toBe(1)
    expect(await received(w.XA)).toBe(1)
    expect(await received(w.A['3'])).toBe(0)
    expect(await received(w.A['4'])).toBe(0)
    expect(await received(w.A['0'])).toBe(0)
    // 画面でも A1 の通知一覧に出る
    const a1 = await openAs(browser, w.A['1'], '/notifications')
    try {
      await expect(a1.page.getByText(S1).first()).toBeVisible({ timeout: 30_000 })
    } finally {
      await a1.context.close()
    }
  })

  test('BC-04 [AC-H09] T1 と T3 の両方に属する B13 は、「G1 と G3」宛てのアンケートで push を1件だけ受け取る', async ({ browser }) => {
    test.setTimeout(600_000)
    const title = `G1とG3のアンケート ${RUN}`
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    try {
      await openWizard(s.page)
      await s.page.getByTestId('broadcast-target-mode-groups').click()
      const preview = s.page.waitForResponse((r) => r.url().includes('/broadcast/audience-preview'), { timeout: 60_000 })
      await s.page.locator(`label[for="group_${w.G['1']}"]`).click()
      await s.page.locator(`label[for="group_${w.G['3']}"]`).click()
      await preview
      await expect(s.page.getByTestId('broadcast-resolved-count')).toContainText('該当: 2 チーム')
      await goChannelAndContent(s.page, 'アンケート', title)
      const sent = await submit(s.page)
      expect(sent.status()).toBeLessThan(300)
    } finally {
      await s.context.close()
    }
    const count = async () => (await notificationsOf(w.B13)).filter((n) => `${n.title ?? ''}${(n as { body?: string }).body ?? ''}`.includes(title)).length
    await expect.poll(count, { timeout: 60_000 }).toBe(1)
    await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
    expect(await count()).toBe(1)
  })

  test('BC-05 [AC-H06] 直属メンバーのいない組織Z で空のグループだけを選ぶと「該当する人がいません」で「次へ」が押せない', async ({ browser }) => {
    test.setTimeout(600_000)
    const s = await openAs(browser, w.A['1'], `/organizations/${w.Z.slug}`)
    try {
      await openWizard(s.page)
      await s.page.getByTestId('broadcast-target-mode-groups').click()
      const preview = s.page.waitForResponse((r) => r.url().includes('/broadcast/audience-preview'), { timeout: 60_000 })
      await s.page.locator(`label[for="group_${w.GZEmpty}"]`).click()
      const body = (await (await preview).json()).data
      expect(body.resolvedTeamCount).toBe(0)
      expect(body.directMemberCount).toBe(0)
      await expect(s.page.getByTestId('broadcast-resolved-count')).toContainText('該当する人がいません')
      await expect(s.page.getByTestId('broadcast-step1-next')).toBeDisabled()
    } finally {
      await s.context.close()
    }
  })

  test('BC-06 [AC-H13/H15] XM（MEMBER）は宛先プレビューで pushEnabled=false の注記が出て、グループ宛てアンケートを送ってもジョブは作られない', async ({ browser }) => {
    test.setTimeout(600_000)
    const title = `XMのアンケート ${RUN}`
    const since = utcNow()
    const s = await openAs(browser, w.XM, `/organizations/${w.X.slug}`)
    try {
      await openWizard(s.page)
      const preview = await chooseUpTo(s.page, GN['2'])
      expect((await preview.json()).data.pushEnabled).toBe(false)
      await goChannelAndContent(s.page, 'アンケート', title)
      await expect(s.page.getByTestId('broadcast-push-disabled-note')).toContainText('このお知らせの push 通知は送られません')
      const sent = await submit(s.page)
      expect(sent.status(), await sent.text()).toBeLessThan(300)
    } finally {
      await s.context.close()
    }
    await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
    expect(jobsSince(w.X.numericId, since)).toEqual([])
    expect((await notificationsOf(w.A['1'])).filter((n) => `${n.title ?? ''}`.includes(title)).length).toBe(0)
  })

  test('BC-07 [AC-H04/H17/H20/H23] 送信後に T3 を G2 へ移すと T3 に出る。G2 を改名しても履歴の宛先は変わらず、G2 削除後は T2 に出続け T3 には出ない', async ({ browser }) => {
    test.setTimeout(1000_000)
    // 準備: T3 を G2 へ移す（割当の画面操作は GRP-03 で検証済み。ここでは表示判定の観測が目的）
    await must(w.XA, 'PUT', `/api/v1/organizations/${w.X.slug}/teams/${w.T['3'].slug}/team-group`, { groupId: w.G['2'] })
    expect(await teamDashboardShows(browser, w.A['3'], w.T['3'], B1), 'AC-H04 T3 に出る').toBe(true)
    // AC-H17: 送信履歴（組織のお知らせ一覧＝/dashboard の「組織」タブ）で、対象の告知 B1 の項目に
    // 「宛先: G2 以前（並び順で先頭から）（送信時 2 チーム）」が出る。宛先名・範囲・件数は送信時の記録なので、
    // G2 を改名しても、削除しても変わらない。
    // （旧製品欠陥 D-2: ダッシュボード API の orgNotices が target_audience を返さず FE も写さなかった。#4014 で修正）
    await must(w.XA, 'PATCH', `/api/v1/organizations/${w.X.slug}/team-groups/${w.G['2']}`, { name: `G2改 ${RUN}` })
    await expectHistoryAudience(browser, '改名後')
    // G2 削除 → T2 はスナップショットで出続け（AC-H20）、T3 は送信時に対象でないので出ない（AC-H23）
    await must(w.XA, 'DELETE', `/api/v1/organizations/${w.X.slug}/team-groups/${w.G['2']}`)
    expect(await teamDashboardShows(browser, w.A['2'], w.T['2'], B1), 'AC-H20 T2 に出続ける').toBe(true)
    expect(await teamDashboardShows(browser, w.A['1'], w.T['1'], B1), 'AC-H20 T1 に出続ける').toBe(true)
    expect(await teamDashboardShows(browser, w.A['3'], w.T['3'], B1), 'AC-H23 T3 には出ない').toBe(false)
    expect(await teamDashboardShows(browser, w.A['4'], w.T['4'], B1)).toBe(false)
    await expectHistoryAudience(browser, '削除後')
  })

  test('BC-08 [AC-H05/H12/K05/H14b] 告知 API の契約（範囲逆転・他組織グループ・TEAM スコープ・併用・501 チーム）', async () => {
    resetUserRateLimit('broadcast:send', w.XA)
    resetUserRateLimit('broadcast:send', w.A['1'])
    const base = `/api/v1/organizations/${w.X.numericId}/broadcast`
    const content = { title: `契約 ${RUN}`, body: '本文' }
    const call = (extra: Record<string, unknown>) =>
      apiAs(w.XA, 'POST', base, { channel: 'BULLETIN_THREAD', targetRole: 'MEMBERS_AND_ABOVE', content, ...extra })
    const reversed = await call({ targetGroupRange: { fromGroupId: w.G['4'], toGroupId: w.G['1'] } })
    expect([reversed.status, reversed.body?.error?.code]).toEqual([400, 'BROADCAST_008'])
    const zGroup = sql(`SELECT BIN_TO_UUID(g.id) FROM org_team_groups g JOIN organizations o ON o.id=g.organization_id WHERE o.slug='${w.Z.slug}' LIMIT 1;`)
    const foreign = await call({ targetGroupIds: [zGroup] })
    expect([foreign.status, foreign.body?.error?.code]).toEqual([400, 'BROADCAST_006'])
    // targetTeamIds はチームの slug（設計書 §8.1。#4014 で slug 化）
    const both = await call({ targetGroupIds: [w.G['1']], targetTeamIds: [w.T['1'].slug] })
    expect([both.status, both.body?.error?.code]).toEqual([400, 'BROADCAST_011'])
    // AC-H14b: 501 チームは 400 BROADCAST_010（件数の上限は slug の解決より先に判定される）
    const tooMany = await call({ targetTeamIds: Array.from({ length: 501 }, (_, i) => `e2e-og-none-${RUN}-${i}`) })
    expect([tooMany.status, tooMany.body?.error?.code], 'AC-H14b 501 チーム').toEqual([400, 'BROADCAST_010'])
    const teamScope = await apiAs(w.A['1'], 'POST', `/api/v1/teams/${w.T['1'].numericId}/broadcast`, {
      channel: 'BULLETIN_THREAD',
      targetRole: 'MEMBERS_AND_ABOVE',
      content,
      targetGroupIds: [w.G['1']],
    })
    expect([teamScope.status, teamScope.body?.error?.code]).toEqual([400, 'BROADCAST_012'])
    // AC-K05: 組織Z の ADMIN が組織X のグループ・チームを宛先に入れる
    const zBase = `/api/v1/organizations/${w.Z.numericId}/broadcast`
    const crossGroup = await apiAs(w.A['1'], 'POST', zBase, { channel: 'BULLETIN_THREAD', targetRole: 'MEMBERS_AND_ABOVE', content, targetGroupIds: [w.G['1']] })
    expect([crossGroup.status, crossGroup.body?.error?.code]).toEqual([400, 'BROADCAST_006'])
    const crossTeam = await apiAs(w.A['1'], 'POST', zBase, { channel: 'BULLETIN_THREAD', targetRole: 'MEMBERS_AND_ABOVE', content, targetTeamIds: [w.T['2'].slug] })
    expect([crossTeam.status, crossTeam.body?.error?.code]).toEqual([400, 'BROADCAST_002'])
    // AC-K07: X の組織ロールを持つメンバーはいるが X に ACTIVE 加盟していないチームを宛先に入れると 400 BROADCAST_002
    const loose = await createTeam(w.XM, `実機未加盟 ${RUN}`)
    const k07 = await call({ targetTeamIds: [loose.slug] })
    expect([k07.status, k07.body?.error?.code], 'K07').toEqual([400, 'BROADCAST_002'])
    await archiveScope('teams', w.XM, loose.slug)
  })

  test('BC-09 [AC-H11/H18/H28] 「すべてのチーム」のアンケートは ORGANIZATION ジョブ1件、「チームを選ぶ」T1 のアンケートは ORGANIZATION_TEAMS（宛先 T1 のみ・push は T1 と XO だけ・表示は T1 だけ）、「チームを選ぶ」の掲示板はジョブなし', async ({ browser }) => {
    test.setTimeout(900_000)
    const send = async (mode: 'all' | 'teams', channel: '掲示板' | 'アンケート', title: string) => {
      // 告知送信のレート制限（5 件/5 分/利用者）は本 AC の対象外のため、前提としてカウンタを戻す
      resetUserRateLimit('broadcast:send', w.XA)
      const since = utcNow()
      const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
      try {
        await openWizard(s.page)
        if (mode === 'teams') {
          await s.page.getByTestId('broadcast-target-mode-teams').click()
          const preview = s.page.waitForResponse((r) => r.url().includes('/broadcast/audience-preview'), { timeout: 60_000 })
          await s.page.getByRole('checkbox', { name: w.T['1'].name, exact: true }).click()
          // 旧製品欠陥 D-3: FE は slug を送り BE は数値 ID を要求していたため、宛先プレビューが 400 COMMON_001 になっていた。
          // #4014 で BE が slug を受けるよう修正。プレビューが 200 で 1 チームに解決することを確かめる。
          const previewed = await preview
          expect(previewed.status(), `チームを選ぶの宛先プレビュー: ${previewed.request().postData()} -> ${await previewed.text()}`).toBe(200)
          await expect(s.page.getByTestId('broadcast-resolved-count')).toContainText('該当: 1 チーム')
        } else {
          await s.page.getByTestId('broadcast-target-mode-all').click()
        }
        await goChannelAndContent(s.page, channel, title)
        const sent = await submit(s.page)
        expect(sent.status(), await sent.text()).toBeLessThan(300)
      } finally {
        await s.context.close()
      }
      await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
      return jobsSince(w.X.numericId, since)
    }
    const all = await send('all', 'アンケート', `全チームのアンケート ${RUN}`)
    expect(all.map((j) => j.scopeType), 'AC-H11/H28').toEqual(['ORGANIZATION'])
    const t1Title = `T1宛てアンケート ${RUN}`
    const one = await send('teams', 'アンケート', t1Title)
    expect(one.map((j) => j.scopeType), 'AC-H18').toEqual(['ORGANIZATION_TEAMS'])
    expect(audienceTeams(firstJob(one).scopeRef), 'AC-H18 宛先').toEqual([w.T['1'].numericId])
    const received = async (actor: Actor) =>
      (await notificationsOf(actor)).filter((n) => `${n.title ?? ''}${(n as { body?: string }).body ?? ''}`.includes(t1Title)).length
    await expect.poll(() => received(w.A['1']), { timeout: 60_000 }).toBe(1)
    expect(await received(w.XO), 'AC-H18 XO').toBe(1)
    expect(await received(w.A['2']), 'AC-H18 T2').toBe(0)
    // AC-H18: T3（A3 は T3 だけに属する ADMIN）・T4 のメンバーにも push は届かない
    expect(await received(w.A['3']), 'AC-H18 T3').toBe(0)
    expect(await received(w.A['4']), 'AC-H18 T4').toBe(0)
    expect(await received(w.A['0']), 'AC-H18 T0').toBe(0)
    // AC-H18: 表示は T1 のダッシュボードにだけ出る（T2・T3・T4・T0 には出ない）
    const shownH18: Record<string, boolean> = {}
    for (const k of KEYS) shownH18[k] = await teamDashboardShows(browser, w.A[k], w.T[k], t1Title)
    expect(shownH18, 'AC-H18 表示は T1 のダッシュボードにだけ').toEqual({ '1': true, '2': false, '3': false, '4': false, '0': false })
    const bulletin = await send('teams', '掲示板', `T1宛て掲示板 ${RUN}`)
    expect(bulletin, 'AC-H28 掲示板').toEqual([])
  })

  /**
   * 「チームを選ぶ」で T1 だけを宛先にして画面から送信する（ステップ1〜3）。
   * 送信本体の targetTeamIds はチームの slug（旧製品欠陥 D-3。#4014 で修正）。
   */
  async function sendToT1ViaUi(browser: import('@playwright/test').Browser, actor: Actor, channel: '掲示板' | 'アンケート', title: string) {
    resetUserRateLimit('broadcast:send', actor)
    const s = await openAs(browser, actor, `/organizations/${w.X.slug}`)
    try {
      await openWizard(s.page)
      await s.page.getByTestId('broadcast-target-mode-teams').click()
      const preview = s.page.waitForResponse((r) => r.url().includes('/broadcast/audience-preview'), { timeout: 60_000 })
      await s.page.getByRole('checkbox', { name: w.T['1'].name, exact: true }).click()
      const previewed = await preview
      expect(previewed.status(), `チームを選ぶの宛先プレビュー（D-3）: ${previewed.request().postData()} -> ${await previewed.text()}`).toBe(200)
      await expect(s.page.getByTestId('broadcast-resolved-count')).toContainText('該当: 1 チーム')
      await goChannelAndContent(s.page, channel, title)
      const sent = await submit(s.page)
      expect(sent.status(), await sent.text()).toBeLessThan(300)
      expect(sent.request().postDataJSON()).toMatchObject({ targetTeamIds: [w.T['1'].slug] })
    } finally {
      await s.context.close()
    }
  }

  test('BC-10 [AC-H27] XM（MEMBER）が「チームを選ぶ」で T1 宛てのアンケートを画面から送ると、表示は T1 にだけ出て、push は誰にも届かずジョブも作られない', async ({ browser }) => {
    test.setTimeout(900_000)
    const title = `XMのT1宛てアンケート ${RUN}`
    const since = utcNow()
    await sendToT1ViaUi(browser, w.XM, 'アンケート', title)
    await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
    expect(jobsSince(w.X.numericId, since), 'AC-H27 ジョブなし').toEqual([])
    const received = async (actor: Actor) =>
      (await notificationsOf(actor)).filter((n) => `${n.title ?? ''}${(n as { body?: string }).body ?? ''}`.includes(title)).length
    expect(await received(w.A['1']), 'AC-H27 T1 に push なし').toBe(0)
    expect(await received(w.XO), 'AC-H27 XO に push なし').toBe(0)
    expect(await teamDashboardShows(browser, w.A['1'], w.T['1'], title), 'AC-H27 T1 に出る').toBe(true)
    expect(await teamDashboardShows(browser, w.A['2'], w.T['2'], title), 'AC-H27 T2 には出ない').toBe(false)
  })

  test('BC-11 [AC-H29] 「チームを選ぶ」で T1 宛ての掲示板を画面から送ると T1 にだけ出て、送信後に T1 が別グループへ移っても T1 に出続ける', async ({ browser }) => {
    test.setTimeout(900_000)
    const title = `T1宛て掲示板（表示確認） ${RUN}`
    await sendToT1ViaUi(browser, w.XA, '掲示板', title)
    expect(await teamDashboardShows(browser, w.A['1'], w.T['1'], title), 'AC-H29 T1 に出る').toBe(true)
    expect(await teamDashboardShows(browser, w.A['2'], w.T['2'], title), 'AC-H29 T2 には出ない').toBe(false)
    expect(await teamDashboardShows(browser, w.A['0'], w.T['0'], title), 'AC-H29 T0 には出ない').toBe(false)
    // 準備: T1 を G1 から G3 へ移す（割当の画面操作は GRP-03 で検証済み。ここでは表示判定の観測が目的）
    await must(w.XA, 'PUT', `/api/v1/organizations/${w.X.slug}/teams/${w.T['1'].slug}/team-group`, { groupId: w.G['3'] })
    expect(await teamDashboardShows(browser, w.A['1'], w.T['1'], title), 'AC-H29 移動後も T1 に出る').toBe(true)
    expect(await teamDashboardShows(browser, w.A['3'], w.T['3'], title), 'AC-H29 移動先グループの T3 には出ない').toBe(false)
  })
})

test.afterAll(() => {
  // 画面が実際に叩いた API のオリジン（検証 BE を見ていることの証拠）。資産の削除は globalTeardown が行う。
  console.log(`[F0121-B] browser API origins ${[...browserApiOrigins].join(',')} (FE ${process.env.BASE_URL})`)
})
