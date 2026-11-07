import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { createActor, createOrg, createTeam, joinAsMember, must, recordAsset, RUN, type Actor, type Scope } from './n1p1-world'

/**
 * CMP-261105-1507 実機E2E の前提データ作成（globalSetup）。API はここ（前提作成）と後始末だけに使う。
 * 画面で確かめる対象（チャットの並び・学期の作成と削除）は spec 側で必ず画面から操作する。
 */
export const WORLD_FILE = path.resolve('tests', 'e2e', '.auth', 'cmp1507-world.json')

export interface World {
  tag: string
  admin: Actor // 組織A・チームT1 のオーナー（ADMIN）
  member: Actor // 組織A・チームT1 の MEMBER（権限なし）
  outsider: Actor // 別組織B・チームTB のオーナー（他テナント）
  orgA: Scope
  orgB: Scope
  t1: Scope
  channel: { id: number; name: string }
  project: { id: number; title: string }
  milestones: { done: string; open: string }
  todos: { done: string; open: string; dueDate: string }
}

export function readWorld(): World {
  if (!existsSync(WORLD_FILE)) {
    throw new Error(`前提データ ${WORLD_FILE} が無い。この spec は playwright-cmp1507.config.ts（専用 globalSetup）から実行すること`)
  }
  return JSON.parse(readFileSync(WORLD_FILE, 'utf8')) as World
}

export default async function globalSetup(): Promise<void> {
  const tag = `c1507${RUN}`
  const admin = await createActor('Admin')
  const member = await createActor('Member')
  const outsider = await createActor('Outsider')

  const orgA = await createOrg(admin, `C1507組織A ${tag}`)
  const orgB = await createOrg(outsider, `C1507組織B ${tag}`)
  await joinAsMember('organizations', admin, orgA.slug, member)

  const t1 = await createTeam(admin, `C1507チーム ${tag}`)
  await createTeam(outsider, `C1507別組織チーム ${tag}`)
  await joinAsMember('teams', admin, t1.slug, member)

  const name = `C1507話題 ${tag}`
  const created = await must(admin, 'POST', '/api/v1/chat/channels', {
    channelType: 'TEAM_PUBLIC',
    teamId: t1.numericId,
    name,
    memberUserIds: [member.id],
  })
  const channel = { id: created.data.id as number, name }
  // チャンネルはチーム削除に連動して消えないため、個別に後始末する
  recordAsset(`channel ${channel.id}`, `/api/v1/chat/channels/${channel.id}`, admin)

  // プロジェクト（#4306 の確認用）: マイルストーン2件（1件完了）、TODO 2件（1件完了・期限と担当者あり。マイルストーンは紐付くTODOの完了で自動的に完了になる）
  const project = (await must(admin, 'POST', `/api/v1/teams/${t1.slug}/projects`, { title: `C1507計画 ${tag}` })).data
  const milestones = { done: `C1507節目済 ${tag}`, open: `C1507節目未 ${tag}` }
  const msDone = (await must(admin, 'POST', `/api/v1/teams/${t1.slug}/projects/${project.id}/milestones`, { title: milestones.done })).data
  await must(admin, 'POST', `/api/v1/teams/${t1.slug}/projects/${project.id}/milestones`, { title: milestones.open })
  const todos = { done: `C1507作業済 ${tag}`, open: `C1507作業未 ${tag}`, dueDate: '2030-04-01' }
  const todoDone = (
    await must(admin, 'POST', `/api/v1/teams/${t1.slug}/todos`, {
      title: todos.done,
      projectId: project.id,
      milestoneId: msDone.id,
      dueDate: todos.dueDate,
      assigneeIds: [member.id],
    })
  ).data
  await must(admin, 'POST', `/api/v1/teams/${t1.slug}/todos`, { title: todos.open, projectId: project.id, dueDate: todos.dueDate, assigneeIds: [admin.id] })
  await must(admin, 'PATCH', `/api/v1/teams/${t1.slug}/todos/${todoDone.id}/status`, { status: 'COMPLETED' })

  const world: World = { tag, admin, member, outsider, orgA, orgB, t1, channel, project: { id: project.id, title: project.title }, milestones, todos }
  mkdirSync(path.dirname(WORLD_FILE), { recursive: true })
  writeFileSync(WORLD_FILE, JSON.stringify(world, null, 2))
  console.log(`[cmp1507] world: tag=${tag} orgA=${orgA.slug} orgB=${orgB.slug} t1=${t1.slug} channel=${channel.id}`)
}
