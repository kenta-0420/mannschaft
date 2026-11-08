import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { createActor, createOrg, createTeam, joinAsMember, must, recordAsset, RUN, type Actor, type Scope } from './n1p1-world'

/**
 * CMP-261103-1225 Phase1 実機E2E の前提データ作成（globalSetup）。API はここ（前提作成）と後始末だけに使う。
 * 作成したデータは WORLD_FILE に書き出し、spec はそれを読む（失敗でワーカーが替わっても作り直さない）。
 * N1P1_REUSE_WORLD=1 のときは既存の WORLD_FILE をそのまま使う（spec の手直し中の再実行用）。
 */
// test-results/ は Playwright が実行開始時に消すため置かない。tests/e2e/.auth/*.json は .gitignore 済み。
export const WORLD_FILE = path.resolve('tests', 'e2e', '.auth', 'n1p1-world.json')

export interface World {
  tag: string
  admin: Actor // 組織A・チームT1/T2 のオーナー（ADMIN）
  member1: Actor // 組織A・チームT1 の MEMBER（時間割あり。チャットを開いて既読にするテストもこの利用者）
  reader: Actor // 未読確認専用の利用者（チームT3 の MEMBER）。どのテストもチャットを開かないので未読が残り続ける
  t3: Scope // 未読確認専用のチーム（名前に tag を含めず、チーム検索の結果に混ざらない）
  unreadChannel: { id: number; name: string } // 未読確認専用のチャンネル（ADMIN の発言2件）
  member2: Actor // 組織A・チームT1 の MEMBER（時間割なし＝空表示確認用）
  outsider: Actor // 別組織B・チームTB のオーナー（他テナント）
  orgA: Scope
  orgB: Scope
  t1: Scope
  t2: Scope
  tb: Scope
  projectFull: { id: number; title: string }
  projectEmpty: { id: number; title: string }
  milestoneTitle: string
  channel: { id: number; name: string }
  emptyChannel: { id: number; name: string }
  timetableId: number
  subjects: string[]
  scheduleTitles: string[]
  attachmentName: string
  messages: string[]
}

export function readWorld(): World {
  if (!existsSync(WORLD_FILE)) {
    throw new Error(`前提データ ${WORLD_FILE} が無い。この spec は playwright-n1p1.config.ts（専用 globalSetup）から実行すること`)
  }
  return JSON.parse(readFileSync(WORLD_FILE, 'utf8')) as World
}

const DAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const

/** 実行時刻から offsetMs だけ先の JST 壁時計（ISO 8601、+09:00 付き・分単位）。 */
function jstIsoFromNow(offsetMs: number): string {
  const jst = new Date(Date.now() + offsetMs + 9 * 3600_000)
  return `${jst.toISOString().slice(0, 16)}:00+09:00`
}

/** JST で days 日ずらした日付（YYYY-MM-DD）。 */
function jstDate(days: number): string {
  return new Date(Date.now() + days * 86_400_000 + 9 * 3600_000).toISOString().slice(0, 10)
}

async function buildWorld(): Promise<World> {
  const tag = `n1p1${RUN}`
  const admin = await createActor('Admin')
  const member1 = await createActor('MemberOne')
  const member2 = await createActor('MemberTwo')
  const outsider = await createActor('Outsider')

  const orgA = await createOrg(admin, `N1検証組織A ${tag}`)
  const orgB = await createOrg(outsider, `N1検証組織B ${tag}`)
  await joinAsMember('organizations', admin, orgA.slug, member1)
  await joinAsMember('organizations', admin, orgA.slug, member2)

  // チーム検索の対象（PUBLIC）。T1 は 3 人、T2 は 1 人。T1 と TB は相互フォロー（友好チーム）。
  const t1 = await createTeam(admin, `N1検証チーム壱 ${tag}`)
  const t2 = await createTeam(admin, `N1検証チーム弐 ${tag}`)
  const tb = await createTeam(outsider, `N1別組織チーム ${tag}`)
  await joinAsMember('teams', admin, t1.slug, member1)
  await joinAsMember('teams', admin, t1.slug, member2)
  await must(admin, 'POST', `/api/v1/teams/${t1.numericId}/friends/follow`, { targetTeamId: tb.numericId })
  await must(outsider, 'POST', `/api/v1/teams/${tb.numericId}/friends/follow`, { targetTeamId: t1.numericId })

  // TODO プロジェクト: 2件（TODO 2件・うち1件完了＋マイルストーンありの物と、TODO 0件の物）
  const projectFull = (await must(admin, 'POST', `/api/v1/teams/${t1.slug}/projects`, { title: `N1計画あり ${tag}` })).data
  const projectEmpty = (await must(admin, 'POST', `/api/v1/teams/${t1.slug}/projects`, { title: `N1計画空 ${tag}` })).data
  const milestoneTitle = `N1節目 ${tag}`
  const milestone = (
    await must(admin, 'POST', `/api/v1/teams/${t1.slug}/projects/${projectFull.id}/milestones`, { title: milestoneTitle })
  ).data
  const todoA = (
    await must(admin, 'POST', `/api/v1/teams/${t1.slug}/todos`, {
      title: `N1作業A ${tag}`,
      projectId: projectFull.id,
      milestoneId: milestone.id,
      assigneeIds: [member1.id, member2.id],
    })
  ).data
  await must(admin, 'POST', `/api/v1/teams/${t1.slug}/todos`, { title: `N1作業B ${tag}`, projectId: projectFull.id, assigneeIds: [member1.id] })
  await must(admin, 'PATCH', `/api/v1/teams/${t1.slug}/todos/${todoA.id}/status`, { status: 'COMPLETED' })

  // チャット: T1 のチャンネル（ADMIN と MEMBER1 の発言＋添付1件）と、発言0件のチャンネル
  // 応答の名前は identity 配下の入れ子なので、送った名前をそのまま持つ
  const createChannel = async (name: string): Promise<{ id: number; name: string }> => {
    const created = await must(admin, 'POST', '/api/v1/chat/channels', {
      channelType: 'TEAM_PUBLIC',
      teamId: t1.numericId,
      name,
      memberUserIds: [member1.id, member2.id],
    })
    return { id: created.data.id, name }
  }
  const channel = await createChannel(`N1話題 ${tag}`)
  const emptyChannel = await createChannel(`N1無言 ${tag}`)
  // チャンネルはチーム削除に連動して消えないため、個別に後始末する（実測: チーム削除後も chat_channels が残った）
  recordAsset(`channel ${channel.id}`, `/api/v1/chat/channels/${channel.id}`, admin)
  recordAsset(`channel ${emptyChannel.id}`, `/api/v1/chat/channels/${emptyChannel.id}`, admin)
  const attachmentName = `n1p1-${RUN}.txt`
  const fileBytes = Buffer.from(`N1 attachment ${RUN}`)
  const upload = (
    await must(admin, 'POST', '/api/v1/chat/files/upload-url', {
      channelId: channel.id,
      fileName: attachmentName,
      contentType: 'text/plain',
      fileSize: fileBytes.length,
    })
  ).data
  const put = await fetch(upload.uploadUrl, { method: 'PUT', headers: { 'content-type': 'text/plain' }, body: fileBytes })
  if (!put.ok) throw new Error(`添付の PUT 失敗 ${put.status}`)
  const messages = [`N1発言管理者 ${tag}`, `N1発言メンバー ${tag}`, `N1発言添付 ${tag}`]
  await must(admin, 'POST', `/api/v1/chat/channels/${channel.id}/messages`, { body: messages[0] })
  await must(member1, 'POST', `/api/v1/chat/channels/${channel.id}/messages`, { body: messages[1] })
  await must(admin, 'POST', `/api/v1/chat/channels/${channel.id}/messages`, {
    body: messages[2],
    attachments: [{ fileKey: upload.fileKey, fileName: attachmentName, fileSize: fileBytes.length, contentType: 'text/plain' }],
  })

  // 未読確認専用: READER と T3・専用チャンネル。チャットを開くテスト（MEMBER1 が既読にする）と状態を共有しない
  // （TEST_CONVENTION.md「テスト間の状態依存の禁止」）。T3 の名前は tag を含めず、チーム検索の期待値に影響させない。
  const reader = await createActor('Reader')
  const t3 = await createTeam(admin, `N1未読検証 ${RUN}`)
  await joinAsMember('teams', admin, t3.slug, reader)
  const unreadChannelName = `N1未読 ${RUN}`
  const unreadCreated = await must(admin, 'POST', '/api/v1/chat/channels', {
    channelType: 'TEAM_PUBLIC',
    teamId: t3.numericId,
    name: unreadChannelName,
    memberUserIds: [reader.id],
  })
  const unreadChannel = { id: unreadCreated.data.id as number, name: unreadChannelName }
  recordAsset(`channel ${unreadChannel.id}`, `/api/v1/chat/channels/${unreadChannel.id}`, admin)
  await must(admin, 'POST', `/api/v1/chat/channels/${unreadChannel.id}/messages`, { body: `N1未読一 ${RUN}` })
  await must(admin, 'POST', `/api/v1/chat/channels/${unreadChannel.id}/messages`, { body: `N1未読二 ${RUN}` })

  // 今日の時間割: MEMBER1 の個人時間割（チーム時間割に未リンクのコマ 2 件）。
  // 実行中に日付をまたいでも「今日」にコマがあるよう、全曜日に同じ2コマを置き、適用開始は前日にする（実行時刻に依存させない）。
  const timetable = (await must(member1, 'POST', '/api/v1/me/personal-timetables', { name: `N1時間割 ${tag}`, effective_from: jstDate(-1) })).data
  recordAsset(`timetable ${timetable.id}`, `/api/v1/me/personal-timetables/${timetable.id}`, member1)
  await must(member1, 'PUT', `/api/v1/me/personal-timetables/${timetable.id}/periods`, {
    periods: [
      { period_number: 1, label: '1限', start_time: '09:00', end_time: '09:50' },
      { period_number: 2, label: '2限', start_time: '10:00', end_time: '10:50' },
    ],
  })
  const subjects = [`N1国語 ${RUN}`, `N1算数 ${RUN}`]
  await must(member1, 'PUT', `/api/v1/me/personal-timetables/${timetable.id}/slots`, {
    slots: DAYS.flatMap((day) => [
      { day_of_week: day, period_number: 1, subject_name: subjects[0], room_name: '101' },
      { day_of_week: day, period_number: 2, subject_name: subjects[1] },
    ]),
  })
  await must(member1, 'POST', `/api/v1/me/personal-timetables/${timetable.id}/activate`)

  // 直近の予定: T1 に予定 2 件。実行時刻から相対で十分先（3時間後・4時間後に開始）に置く
  // （固定の時刻だと夜に走らせたとき過去になり「今後の予定」から外れる。TEST_CONVENTION.md の相対日時）。
  // 画面側は直近予定ウィジェットの既定「今週」（7日間）とスコープの「今後の予定」で見るため、日付をまたいでも外れない。
  const scheduleTitles = [`N1予定一 ${tag}`, `N1予定二 ${tag}`]
  const HOUR = 3_600_000
  for (const [index, title] of scheduleTitles.entries()) {
    await must(admin, 'POST', `/api/v1/teams/${t1.slug}/schedules`, {
      title,
      startAt: jstIsoFromNow((3 + index) * HOUR),
      endAt: jstIsoFromNow((4 + index) * HOUR),
      allDay: false,
      eventType: 'EVENT',
      attendanceRequired: false,
    })
  }

  return {
    tag,
    admin,
    member1,
    reader,
    t3,
    unreadChannel,
    member2,
    outsider,
    orgA,
    orgB,
    t1,
    t2,
    tb,
    projectFull: { id: projectFull.id, title: projectFull.title },
    projectEmpty: { id: projectEmpty.id, title: projectEmpty.title },
    milestoneTitle,
    channel: { id: channel.id, name: channel.name },
    emptyChannel: { id: emptyChannel.id, name: emptyChannel.name },
    timetableId: timetable.id,
    subjects,
    scheduleTitles,
    attachmentName,
    messages,
  }
}

export default async function globalSetup(): Promise<void> {
  if (process.env.N1P1_REUSE_WORLD === '1' && existsSync(WORLD_FILE)) {
    console.log(`[n1p1] world 再利用: ${WORLD_FILE}`)
    return
  }
  const world = await buildWorld()
  mkdirSync(path.dirname(WORLD_FILE), { recursive: true })
  writeFileSync(WORLD_FILE, JSON.stringify(world, null, 2))
  console.log(`[n1p1] world: tag=${world.tag} orgA=${world.orgA.slug} orgB=${world.orgB.slug} t1=${world.t1.slug} t2=${world.t2.slug} tb=${world.tb.slug} channel=${world.channel.id}`)
}
