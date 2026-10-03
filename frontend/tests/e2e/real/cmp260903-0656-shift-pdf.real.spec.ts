/**
 * CMP-260903-0656: Actions専用DBの実JWT/controller/PDF診断。
 * fixtureは正規APIだけで作成し、自分のID・作成者・名前を照合して削除する。
 * HTTP診断とboardの1回の実downloadを区別し、保存PDFの全ページ画像検分は後段で行う。
 */
import {
  test,
  expect,
  request,
  type APIRequestContext,
  type APIResponse,
  type TestInfo,
} from '@playwright/test'
import {
  readFile,
  writeFile,
  mkdir,
  readdir,
  mkdtemp,
  rm,
} from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const API = `${process.env.API_BASE_URL ?? 'http://localhost:8080'}/api/v1`
const EMAILS = [
  'e2e-dummy-4@test.mannschaft.local',
  'e2e-dummy-5@test.mannschaft.local',
] as const
const layouts = ['team', 'personal'] as const
type Layout = (typeof layouts)[number]
type Me = {
  id: number
  email: string
  systemRole?: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  timezone?: string
}
type Actor = { ctx: APIRequestContext; token: string; me: Me }
type Schedule = {
  id: number
  teamId: number
  content: { title: string }
  audit: { createdBy: number }
  status: { status: string; publishedAt: string | null }
}
type Team = {
  slug: string
  numericId: number
  basicInfo: { name: string }
  visibility: { visibility: string }
}
type Position = { id: number; teamId: number; name: string }

test.use({
  storageState: undefined,
  trace: 'off',
  screenshot: 'off',
  video: 'off',
})

/** リクエスト例外にAuthorization/認証本文を含めない。アサートはstatusだけを出す。 */
async function api(
  actor: Actor,
  method: string,
  route: string,
  data?: unknown,
): Promise<APIResponse> {
  try {
    return await actor.ctx.fetch(`${API}${route}`, {
      method,
      data,
      headers: { Authorization: `Bearer ${actor.token}` },
    })
  } catch {
    throw new Error(
      `API通信失敗: ${method} ${route.split('?')[0].replace(/\/invite\/[^/]+/, '/invite/[redacted]')}`,
    )
  }
}
async function data<T>(
  actor: Actor,
  method: string,
  route: string,
  status = 200,
  body?: unknown,
): Promise<T> {
  const response = await api(actor, method, route, body)
  expect(
    response.status(),
    `${method} ${route.split('?')[0].replace(/\/invite\/[^/]+/, '/invite/[redacted]')}`,
  ).toBe(status)
  try {
    return (await response.json()).data as T
  } catch {
    throw new Error('APIのJSON契約不一致（本文非出力）')
  }
}
async function login(email: string): Promise<Actor> {
  const ctx = await request.newContext({ storageState: undefined })
  try {
    // 既存specのcanonical seed credentialをRAMで読む。新しい資格情報を作らない。
    const source = await readFile(
      path.resolve('tests/e2e/real/shift-unpublished-visibility.spec.ts'),
      'utf8',
    )
    const password = /const MEMBER_PASSWORD = [^\n]*\?\? '([^']+)'/.exec(
      source,
    )?.[1]
    if (!password) throw new Error('canonical credentialを解決できない')
    const response = await ctx.post(`${API}/auth/login`, {
      data: { email, password },
    })
    if (response.status() !== 200)
      throw new Error('canonical login status不一致')
    const token: string = (await response.json()).data.accessToken
    if (typeof token !== 'string' || !token) throw new Error('JWT未発行')
    const actor = { ctx, token, me: {} as Me }
    actor.me = await data<Me>(actor, 'GET', '/users/me')
    if (
      actor.me.email !== email ||
      !Number.isSafeInteger(actor.me.id) ||
      actor.me.systemRole === 'SYSTEM_ADMIN'
    ) {
      throw new Error('予約した非SYSTEM_ADMIN役者との一致失敗')
    }
    return actor
  } catch {
    await ctx.dispose()
    throw new Error('canonical役者の実JWT/me照合失敗（認証本文非出力）')
  }
}

/** Actionsの実Java起動envと既存ログで外部pushが未初期化であることを公開前に確認。 */
async function requireIsolatedRuntime(): Promise<void> {
  expect(process.env.GITHUB_ACTIONS).toBe('true')
  expect(process.env.E2E_ISOLATED_DB).toBe('true')
  expect(process.env.E2E_DB_NAME).toBe('mannschaft')
  expect(new URL(API).hostname).toBe('localhost')
  const pid = process.env.BACKEND_PID
  expect(Boolean(pid && /^\d+$/.test(pid)), 'workflow所有Java PID必須').toBe(
    true,
  )
  const environment = await readFile(`/proc/${pid}/environ`, 'utf8')
  const values = new Map(
    environment
      .split('\0')
      .filter(Boolean)
      .map((line) => {
        const split = line.indexOf('=')
        return [line.slice(0, split), line.slice(split + 1)]
      }),
  )
  for (const key of [
    'VAPID_PUBLIC_KEY',
    'VAPID_PRIVATE_KEY',
    'AWS_ACCESS_KEY_ID',
    'AWS_SECRET_ACCESS_KEY',
    'STRIPE_SECRET_KEY',
    'LINE_CHANNEL_ACCESS_TOKEN',
  ]) {
    expect(Boolean(values.get(key)), `${key}外部資格情報なし`).toBe(false)
  }
  const command = await readFile(`/proc/${pid}/cmdline`, 'utf8')
  expect(command.includes('--spring.profiles.active=ci')).toBe(true)
  const log = await readFile('/tmp/backend.log', 'utf8')
  expect(
    log.includes('VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY が未設定です'),
    '実runtimeでWebPush未初期化',
  ).toBe(true)
  // ci正本はemail.simulate=true/outbox.worker.enabled=false。公開通知の配送経路はWS/STORED内部通知とWebPushのみ。
}

class OwnedFixture {
  readonly tag = `pdf0656-${randomUUID().slice(0, 8)}`
  readonly name = `PDF診断_${this.tag}`
  team?: Team
  teamCreated = false
  readonly schedules: Schedule[] = []
  readonly positions: Position[] = []
  inviteId?: number
  readonly day = new Date(Date.now() + 30 * 86400_000)
    .toISOString()
    .slice(0, 10)
  constructor(
    readonly owner: Actor,
    readonly member: Actor,
  ) {}

  async createTeam(): Promise<void> {
    const response = await api(this.owner, 'POST', '/teams', {
      slug: this.tag,
      name: this.name,
      visibility: 'MEMBERS_AND_ABOVE',
    })
    expect(response.status()).toBe(201)
    this.teamCreated = true
    this.team = await data<Team>(this.owner, 'GET', `/teams/${this.tag}`)
    await this.guardTeam()
    expect(this.team.visibility.visibility).toBe('MEMBERS_AND_ABOVE')
    const permissions = await data<{ roleName: string }>(
      this.owner,
      'GET',
      `/teams/${this.tag}/me/permissions`,
    )
    expect(permissions.roleName).toBe('ADMIN')
  }
  async guardTeam(): Promise<Team> {
    const actual = await data<Team>(this.owner, 'GET', `/teams/${this.tag}`)
    expect(actual.slug).toBe(this.tag)
    expect(actual.basicInfo.name).toBe(this.name)
    if (this.team) expect(actual.numericId).toBe(this.team.numericId)
    return actual
  }
  async joinMember(): Promise<void> {
    const invite = await data<{ id: number; token: string; roleName: string }>(
      this.owner,
      'POST',
      `/teams/${this.tag}/invite-tokens`,
      201,
      { roleId: 4, expiresIn: '1d', maxUses: 1 },
    )
    this.inviteId = invite.id
    expect(invite.roleName).toBe('MEMBER')
    const joined = await api(
      this.member,
      'POST',
      `/invite/${invite.token}/join`,
      {},
    )
    expect(joined.ok(), '内部tokenでのみ参加、メール招待なし').toBe(true)
    const permissions = await data<{ roleName: string }>(
      this.member,
      'GET',
      `/teams/${this.tag}/me/permissions`,
    )
    expect(permissions.roleName).toBe('MEMBER')
    const members = await data<Array<{ userId: number }>>(
      this.owner,
      'GET',
      `/teams/${this.tag}/members/all`,
    )
    expect(members.map((m) => m.userId).sort((a, b) => a - b)).toEqual(
      [this.owner.me.id, this.member.me.id].sort((a, b) => a - b),
    )
  }
  async position(suffix: string): Promise<Position> {
    const position = await data<Position>(
      this.owner,
      'POST',
      `/shifts/positions?teamId=${this.team!.numericId}`,
      201,
      {
        name: `${this.tag}_${suffix}`,
        displayOrder: this.positions.length + 1,
      },
    )
    this.positions.push(position)
    return position
  }
  async schedule(suffix: string): Promise<Schedule> {
    const schedule = await data<Schedule>(
      this.owner,
      'POST',
      `/shifts/schedules?teamId=${this.team!.numericId}`,
      201,
      {
        title: `${this.tag}_${suffix}`,
        startDate: this.day,
        endDate: this.day,
      },
    )
    this.schedules.push(schedule)
    return schedule
  }
  async slot(
    schedule: Schedule,
    position: Position,
    userId: number,
    startTime: string,
  ): Promise<void> {
    const slot = await data<{ id: number }>(
      this.owner,
      'POST',
      `/shifts/schedules/${schedule.id}/slots`,
      201,
      {
        slotDate: this.day,
        startTime,
        endTime: startTime === '08:30:00' ? '10:00:00' : '15:00:00',
        positionId: position.id,
        requiredCount: 1,
        note: null,
      },
    )
    expect(
      (
        await api(this.owner, 'PATCH', `/shifts/slots/${slot.id}/assignments`, {
          addUserIds: [userId],
          removeUserIds: [],
          slotVersion: 0,
        })
      ).status(),
    ).toBe(200)
  }
  async transition(schedule: Schedule, status: string): Promise<void> {
    const response = await data<Schedule>(
      this.owner,
      'POST',
      `/shifts/schedules/${schedule.id}/transition?status=${status}`,
    )
    expect(response.status.status).toBe(status)
    if (status === 'PUBLISHED')
      expect(response.status.publishedAt).not.toBeNull()
    if (status === 'ARCHIVED') expect(response.status.publishedAt).toBeNull()
  }
  async cleanup(): Promise<void> {
    if (!this.teamCreated) return
    const failures: Error[] = []
    try {
      await this.guardTeam()
    } catch {
      throw new Error('所有team照合失敗: 削除を中止')
    }
    for (const schedule of [...this.schedules].reverse()) {
      try {
        const actual = await data<Schedule>(
          this.owner,
          'GET',
          `/shifts/schedules/${schedule.id}`,
        )
        expect(actual.teamId).toBe(this.team!.numericId)
        expect(actual.content.title).toBe(schedule.content.title)
        expect(actual.audit.createdBy).toBe(this.owner.me.id)
        expect(
          (
            await api(this.owner, 'DELETE', `/shifts/schedules/${schedule.id}`)
          ).status(),
        ).toBe(204)
        expect(
          (
            await api(this.owner, 'GET', `/shifts/schedules/${schedule.id}`)
          ).status(),
        ).toBe(404)
      } catch {
        failures.push(new Error(`自己schedule後始末失敗 id=${schedule.id}`))
      }
    }
    for (const position of [...this.positions].reverse()) {
      try {
        const all = await data<Position[]>(
          this.owner,
          'GET',
          `/shifts/positions?teamId=${this.team!.numericId}`,
        )
        const actual = all.find((p) => p.id === position.id)
        expect(actual?.name).toBe(position.name)
        expect(actual?.teamId).toBe(this.team!.numericId)
        expect(
          (
            await api(this.owner, 'DELETE', `/shifts/positions/${position.id}`)
          ).status(),
        ).toBe(204)
        const after = await data<Position[]>(
          this.owner,
          'GET',
          `/shifts/positions?teamId=${this.team!.numericId}`,
        )
        expect(after.some((p) => p.id === position.id)).toBe(false)
      } catch {
        failures.push(new Error(`自己position後始末失敗 id=${position.id}`))
      }
    }
    if (this.inviteId) {
      try {
        const invites = await data<Array<{ id: number }>>(
          this.owner,
          'GET',
          `/teams/${this.tag}/invite-tokens`,
        )
        expect(invites.some((i) => i.id === this.inviteId)).toBe(true)
        expect(
          (
            await api(
              this.owner,
              'DELETE',
              `/teams/${this.tag}/invite-tokens/${this.inviteId}`,
            )
          ).status(),
        ).toBe(204)
      } catch {
        failures.push(new Error('自己invite後始末失敗'))
      }
    }
    // 子の削除が失敗した場合、team削除で結果を隠さない。
    if (failures.length)
      throw new AggregateError(failures, '自己fixture後始末不合格')
    await this.guardTeam()
    expect(
      (await api(this.owner, 'DELETE', `/teams/${this.tag}`)).status(),
    ).toBe(204)
    expect((await api(this.owner, 'GET', `/teams/${this.tag}`)).status()).toBe(
      404,
    )
  }
}

/** 初期化から保護し、二人目のlogin失敗でも一人目のctxを閉じる。 */
async function withFixture(
  run: (owner: Actor, member: Actor, fixture: OwnedFixture) => Promise<void>,
): Promise<void> {
  const actors: Actor[] = []
  let fixture: OwnedFixture | undefined
  let failure: unknown
  const cleanupFailures: Error[] = []
  try {
    const owner = await login(EMAILS[0])
    actors.push(owner)
    const member = await login(EMAILS[1])
    actors.push(member)
    expect(member.me.id).not.toBe(owner.me.id)
    fixture = new OwnedFixture(owner, member)
    await run(owner, member, fixture)
  } catch (error) {
    failure = error
  } finally {
    try {
      await fixture?.cleanup()
    } catch {
      cleanupFailures.push(new Error('自己fixture後始末不合格'))
    }
    for (const actor of actors.reverse()) {
      try {
        await actor.ctx.dispose()
      } catch {
        cleanupFailures.push(new Error('自己API context閉鎖失敗'))
      }
    }
  }
  if (failure && cleanupFailures.length)
    throw new AggregateError(
      [failure, ...cleanupFailures],
      '検証失敗と後始末失敗（秘密/上流stackをログ出力しない）',
    )
  if (failure) throw failure
  if (cleanupFailures.length)
    throw new AggregateError(cleanupFailures, '後始末不合格')
}

/** 準備済みresourceは検証失敗時も閉鎖し、元の失敗をcleanup例外で上書きしない。 */
async function withCleanup(
  run: () => Promise<void>,
  close: () => Promise<void>,
): Promise<void> {
  let failure: unknown
  try {
    await run()
  } catch (error) {
    failure = error
  }
  try {
    await close()
  } catch (error) {
    if (failure)
      throw new AggregateError([failure, error], '検証と自己resource閉鎖の失敗')
    throw error
  }
  if (failure) throw failure
}

/** 本番JARに既存のOpenPDFだけを利用し、新依存を追加せずPDF本文を読む。 */
async function pdfInspector(): Promise<{
  text: (file: string) => string
  close: () => Promise<void>
}> {
  const toolDir = await mkdtemp(path.join(tmpdir(), 'cmp0656-pdf-inspector-'))
  const close = async () => {
    if (
      path.dirname(toolDir) !== path.resolve(tmpdir()) ||
      !path.basename(toolDir).startsWith('cmp0656-pdf-inspector-')
    ) {
      throw new Error('自己PDF toolDir所有照合失敗')
    }
    await rm(toolDir, { recursive: true, force: true })
  }
  try {
    const jarDir = path.resolve('../backend/build/libs')
    const jars = (await readdir(jarDir)).filter(
      (n) => n.endsWith('.jar') && !n.endsWith('-plain.jar'),
    )
    expect(jars.length).toBe(1)
    execFileSync(
      'jar',
      ['xf', path.join(jarDir, jars[0]!), 'BOOT-INF/lib/openpdf-2.0.3.jar'],
      { cwd: toolDir, stdio: 'pipe' },
    )
    const source = `import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
public class ReadShiftPdf {
  public static void main(String[] args) throws Exception {
    PdfReader reader = new PdfReader(args[0]);
    try {
      if (reader.getNumberOfPages() < 1) throw new IllegalStateException("empty PDF");
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      for (int i=1;i<=reader.getNumberOfPages();i++) System.out.println(extractor.getTextFromPage(i));
    } finally { reader.close(); }
  }
}`
    await writeFile(path.join(toolDir, 'ReadShiftPdf.java'), source)
    const classpath = path.join(toolDir, 'BOOT-INF/lib/openpdf-2.0.3.jar')
    return {
      text: (file) =>
        execFileSync(
          'java',
          [
            '-Djava.awt.headless=true',
            '--class-path',
            classpath,
            path.join(toolDir, 'ReadShiftPdf.java'),
            file,
          ],
          { encoding: 'utf8', stdio: 'pipe' },
        ),
      close,
    }
  } catch (error) {
    try {
      await close()
    } catch {
      throw new AggregateError(
        [error, new Error('自己toolDir準備失敗後の削除失敗')],
        'PDF検分準備不合格',
      )
    }
    throw error
  }
}
async function pdf(
  actor: Actor,
  schedule: Schedule,
  layout: Layout,
  label: string,
  info: TestInfo,
): Promise<string> {
  const response = await api(
    actor,
    'GET',
    `/shifts/schedules/${schedule.id}/pdf?layout=${layout}`,
  )
  expect(response.status(), `${label}-${layout}`).toBe(200)
  expect(response.headers()['content-type']).toContain('application/pdf')
  expect(response.headers()['content-disposition']).toContain(
    `filename="shift-${layout}-${schedule.id}.pdf"`,
  )
  const bytes = await response.body()
  expect(bytes.subarray(0, 5).toString('ascii')).toBe('%PDF-')
  expect(Number(response.headers()['content-length'])).toBe(bytes.length)
  const file = info.outputPath('pdf', `${label}-${layout}.pdf`)
  await mkdir(path.dirname(file), { recursive: true })
  await writeFile(file, bytes)
  expect((await readFile(file)).equals(bytes)).toBe(true)
  return file
}

// それぞれ独立したscopeを持ち、前テストのfixture/実行順へ依存しない。
test('公開PDF: empty/populated×両layoutとMEMBER本人フィルタ、board自然download', async ({
  browser,
}, info) => {
  await requireIsolatedRuntime()
  await withFixture(async (owner, member, fixture) => {
    const inspector = await pdfInspector()
    await withCleanup(
      async () => {
        await fixture.createTeam()
        await fixture.joinMember()
        const ownerPosition = await fixture.position('owner-only')
        const memberPosition = await fixture.position('member-only')
        const populated = await fixture.schedule('populated')
        await fixture.slot(populated, ownerPosition, owner.me.id, '08:30:00')
        await fixture.slot(populated, memberPosition, member.me.id, '13:00:00')
        const empty = await fixture.schedule('empty')
        for (const schedule of [populated, empty]) {
          await fixture.transition(schedule, 'COLLECTING')
          await fixture.transition(schedule, 'ADJUSTING')
          await fixture.transition(schedule, 'PUBLISHED')
          for (const [label, actor, ownPosition, otherPosition] of [
            ['owner', owner, ownerPosition, memberPosition],
            ['member', member, memberPosition, ownerPosition],
          ] as const) {
            for (const layout of layouts) {
              const file = await pdf(
                actor,
                schedule,
                layout,
                `${label}-${schedule === empty ? 'empty' : 'populated'}`,
                info,
              )
              const text = inspector.text(file).replace(/\s+/g, ' ')
              expect(text).toContain(schedule.content.title)
              expect(text).toContain(fixture.day)
              expect(text).toContain(
                layout === 'team' ? 'チーム全体シフト表' : '個人タイムライン',
              )
              if (schedule === populated) {
                expect(text).toContain(ownPosition.name)
                if (layout === 'personal') {
                  expect(text).not.toContain(otherPosition.name)
                  expect(text).toContain('合計シフト数: 1 件')
                  expect(text).not.toContain('割り当てられたシフトはありません')
                } else {
                  expect(text).toContain(otherPosition.name)
                  expect(text).toContain('08:30')
                  expect(text).toContain('13:00')
                }
              } else if (layout === 'personal') {
                expect(text).toContain('割り当てられたシフトはありません')
                expect(text).toContain('合計シフト数: 0 件')
              } else {
                expect(text).toContain('割当メンバー')
                expect(text).not.toContain(ownerPosition.name)
                expect(text).not.toContain(memberPosition.name)
              }
            }
          }
        }
        // 正規API loginのCookieをRAMで引き継ぐ（storageStateファイルは生成しない）。
        const context = await browser.newContext({
          storageState: undefined,
          locale: 'ja-JP',
          baseURL: process.env.BASE_URL ?? 'http://localhost:8081',
        })
        await withCleanup(
          async () => {
            const cookies = (await owner.ctx.storageState()).cookies
            await context.addCookies(cookies)
            const expiresAt = cookies.find(
              (cookie) => cookie.name === 'access_token',
            )?.expires
            expect(Boolean(expiresAt && expiresAt > 0)).toBe(true)
            await context.addInitScript(
              ({ me, expiresAt }) => {
                localStorage.setItem(
                  'currentUser',
                  JSON.stringify({
                    id: me.id,
                    email: me.email,
                    fullName: `${me.lastName} ${me.firstName}`,
                    profileImageUrl: me.avatarUrl,
                    systemRole: me.systemRole,
                    timezone: me.timezone,
                  }),
                )
                localStorage.setItem(
                  'tokenExpiresAt',
                  String(expiresAt! * 1000),
                )
              },
              { me: owner.me, expiresAt },
            )
            const page = await context.newPage()
            await page.goto(
              `/teams/${fixture.tag}/shifts/${populated.id}/board`,
              { waitUntil: 'domcontentloaded' },
            )
            await waitForHydration(page)
            await waitForSpinnerGone(page)
            await expect(
              page.getByText(populated.content.title, { exact: true }),
            ).toBeVisible()
            const button = page.getByRole('button', {
              name: 'チーム全体表 PDF',
              exact: true,
            })
            await expect(button).toBeVisible()
            await expect(button).toBeEnabled()
            const downloadPromise = page.waitForEvent('download')
            await button.click()
            const download = await downloadPromise
            expect(download.suggestedFilename()).toBe(
              `shift-team-${populated.id}.pdf`,
            )
            const file = info.outputPath('pdf', 'ui-owner-team.pdf')
            await download.saveAs(file)
            expect(
              (await readFile(file)).subarray(0, 5).toString('ascii'),
            ).toBe('%PDF-')
            expect(inspector.text(file)).toContain(populated.content.title)
            await page.screenshot({
              path: info.outputPath('pdf', 'ui-download-board.png'),
            })
          },
          () => context.close(),
        )
      },
      () => inspector.close(),
    )
  })
})

test('PDF境界: 未認証401、非所属404、MEMBER未公開4状態×両layout404', async () => {
  await requireIsolatedRuntime()
  await withFixture(async (owner, member, fixture) => {
    const unauthenticated = await request.newContext({
      storageState: undefined,
    })
    await withCleanup(
      async () => {
        await fixture.createTeam()
        const draft = await fixture.schedule('DRAFT')
        for (const layout of layouts) {
          const response = await unauthenticated.get(
            `${API}/shifts/schedules/${draft.id}/pdf?layout=${layout}`,
          )
          expect(response.status()).toBe(401)
          // getScheduleは可視性を先に評価し、越境もSHIFT_001へ畳む。現controller経路の契約は404。
          expect(
            (
              await api(
                member,
                'GET',
                `/shifts/schedules/${draft.id}/pdf?layout=${layout}`,
              )
            ).status(),
          ).toBe(404)
        }
        await fixture.joinMember()
        for (const status of ['DRAFT', 'COLLECTING', 'ADJUSTING', 'ARCHIVED']) {
          const schedule =
            status === 'DRAFT' ? draft : await fixture.schedule(status)
          if (status === 'COLLECTING' || status === 'ADJUSTING')
            await fixture.transition(schedule, 'COLLECTING')
          if (status === 'ADJUSTING')
            await fixture.transition(schedule, 'ADJUSTING')
          if (status === 'ARCHIVED')
            await fixture.transition(schedule, 'ARCHIVED')
          // ARCHIVEDはDRAFTからの正規API遷移。publishedAt捏造/SQL書込みなし。
          const actual = await data<Schedule>(
            owner,
            'GET',
            `/shifts/schedules/${schedule.id}`,
          )
          expect(actual.status.publishedAt).toBeNull()
          for (const layout of layouts) {
            const response = await api(
              member,
              'GET',
              `/shifts/schedules/${schedule.id}/pdf?layout=${layout}`,
            )
            expect(response.status(), `${status}-${layout}`).toBe(404)
          }
        }
      },
      () => unauthenticated.dispose(),
    )
  })
})
