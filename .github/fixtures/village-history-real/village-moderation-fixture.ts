// 履歴の不変witnessを完了した後だけ、新fake3・村1を正規APIで追加する。
import { createHash } from 'node:crypto'
import type { Actor, SeedDb, Row } from './village-history-fixture-helper.ts'

export type ModerationManifest = {
  villageId: string; creationRequestId: string; requestIds: string[]
  actors: { userId: number; membershipId: string; credentialEnvPrefix: string;
    role: 'HEADMAN' | 'ELDER' | 'VILLAGER' }[]
  banReason: string
}
type Member = { id: string; subjectId: number; subjectType: string; role: string }
const tables = ['villages', 'village_creation_requests', 'village_join_requests', 'village_memberships'] as const
const digest = (rows: unknown) => createHash('sha256').update(JSON.stringify(rows)).digest('hex')
const binaryId = (id: string) => {
  if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error('MODERATION_UUID_INVALID')
  return id.replaceAll('-', '')
}
async function call<T>(actor: Actor, url: string, method: 'POST' | 'PATCH', data: unknown, status = 200) {
  const response = await actor.api.fetch(url, { method, data })
  if (response.status() !== status) throw new Error('MODERATION_SETUP_HTTP_FAILED')
  return (await response.json()).data as T
}

// private DB全行は比較メモリ内だけ。artifactへは件数とdigestのみを渡す。
export async function captureHistoryBoundary(db: SeedDb) {
  const snapshots = new Map<string, string>()
  for (const table of [...tables, 'users', 'user_roles']) {
    const [rows] = await db.query(`SELECT * FROM ${table} ORDER BY id`, [])
    snapshots.set(table, digest(rows))
  }
  return async (manifest: ModerationManifest, addedUserIds: number[]) => {
    const village = binaryId(manifest.villageId)
    for (const table of tables) {
      const key = table === 'villages' ? 'id' : table === 'village_creation_requests' ? 'created_village_id' : 'village_id'
      const [rows] = await db.query(`SELECT * FROM ${table} WHERE (${key} <> UNHEX(?) OR ${key} IS NULL) ORDER BY id`, [village])
      if (digest(rows) !== snapshots.get(table)) throw new Error('HISTORY_OWNED_BOUNDARY_CHANGED')
    }
    const [users] = await db.query('SELECT * FROM users WHERE id NOT IN (?,?,?) ORDER BY id', addedUserIds)
    const [roles] = await db.query('SELECT * FROM user_roles ORDER BY id', [])
    if (digest(users) !== snapshots.get('users') || digest(roles) !== snapshots.get('user_roles'))
      throw new Error('HISTORY_USERS_OR_ROLES_CHANGED')
    return { previousHistoryExactUnchanged: true, historyBounds: { requests: 22, memberships: 12, villages: 6, creationRequests: 6, users: 3 } }
  }
}

export async function setupModerationFixture(db: SeedDb, actors: [Actor, Actor, Actor], runKey: string) {
  const [headman, elder, villager] = actors
  const slug = `vh-${runKey}-moderation`
  const [collision] = await db.query('SELECT id FROM villages WHERE slug = ?', [slug])
  if (collision.length) throw new Error('MODERATION_SLUG_COLLISION')
  const creation = await call<{ id: string; status: string; createdVillageId: string }>(headman,
    '/api/v1/villages/creation-requests', 'POST', { name: slug, slug, purpose: '村権限実機fixture',
      type: 'COMMUNITY', guidelineAgreedAt: new Date().toISOString() }, 201)
  if (creation.status !== 'APPROVED' || !creation.createdVillageId) throw new Error('MODERATION_CREATION_NOT_APPROVED')
  const villageId = creation.createdVillageId
  await call(headman, `/api/v1/villages/${villageId}`, 'PATCH', { joinPolicy: 'APPROVAL' })
  const requestIds: string[] = []
  for (const applicant of [elder, villager]) {
    const pending = await call<Row>(applicant, `/api/v1/villages/${villageId}/join-requests`, 'POST', {
      subjectType: 'USER', subjectId: applicant.userId, message: `${runKey}-moderation-${applicant.userId}` }, 201)
    if (pending.villageId !== villageId || pending.subjectId !== applicant.userId
      || pending.subjectType !== 'USER' || pending.status !== 'PENDING')
      throw new Error('MODERATION_REQUEST_PARENT_MISMATCH')
    requestIds.push(pending.id)
    const approved = await call<Row>(headman, `/api/v1/villages/${villageId}/join-requests/${pending.id}/approve`, 'POST', {})
    if (approved.id !== pending.id || approved.villageId !== villageId || approved.subjectId !== applicant.userId
      || approved.subjectType !== 'USER' || approved.status !== 'APPROVED')
      throw new Error('MODERATION_APPROVAL_MISMATCH')
  }
  const response = await headman.api.get(`/api/v1/villages/${villageId}/memberships?page=0&size=50`)
  if (response.status() !== 200) throw new Error('MODERATION_MEMBERS_READ_FAILED')
  const members = (await response.json()).data.content as Member[]
  if (members.length !== 3) throw new Error('MODERATION_MEMBER_BOUNDS_MISMATCH')
  const actorRows = actors.map((actor, index) => {
    const matches = members.filter((m) => m.subjectType === 'USER' && Number(m.subjectId) === actor.userId)
    if (matches.length !== 1 || matches[0].role !== (index === 0 ? 'HEADMAN' : 'VILLAGER'))
      throw new Error('MODERATION_MEMBER_PRINCIPAL_MISMATCH')
    return { userId: actor.userId, membershipId: matches[0].id, credentialEnvPrefix: actor.credentialEnvPrefix,
      role: (['HEADMAN', 'ELDER', 'VILLAGER'] as const)[index] }
  })
  const changed = await call<Member>(headman, `/api/v1/villages/${villageId}/memberships/${actorRows[1].membershipId}/role`, 'PATCH', { role: 'ELDER' })
  if (changed.id !== actorRows[1].membershipId || changed.role !== 'ELDER') throw new Error('MODERATION_ELDER_SETUP_FAILED')
  const manifest: ModerationManifest = { villageId, creationRequestId: creation.id, requestIds,
    actors: actorRows, banReason: `${runKey}-owned-ban` }
  const frozen = new Map<string, string>()
  for (const table of tables) {
    const [rows] = await db.query(`SELECT * FROM ${table} WHERE ${table === 'villages' ? 'id' : table === 'village_creation_requests' ? 'created_village_id' : 'village_id'} = UNHEX(?) ORDER BY id`, [binaryId(villageId)])
    if (rows.length !== (table === 'village_memberships' ? 3 : table === 'village_join_requests' ? 2 : 1))
      throw new Error('MODERATION_DB_BOUNDS_MISMATCH')
    const expectedIds = table === 'villages' ? [villageId] : table === 'village_creation_requests' ? [creation.id]
      : table === 'village_join_requests' ? requestIds : actorRows.map((a) => a.membershipId)
    const actualIds = (rows as { id: Buffer }[]).map((r) => r.id.toString('hex').toLowerCase()).sort()
    if (JSON.stringify(actualIds) !== JSON.stringify(expectedIds.map(binaryId).map((id) => id.toLowerCase()).sort()))
      throw new Error('MODERATION_DB_OWNED_IDS_MISMATCH')
    frozen.set(table, digest(rows))
  }
  const [memberBefore] = await db.query('SELECT * FROM village_memberships WHERE village_id=UNHEX(?) ORDER BY id', [binaryId(villageId)])
  return { manifest, assertExpectedMutation: async () => {
    for (const table of tables.filter((t) => t !== 'village_memberships')) {
      const [rows] = await db.query(`SELECT * FROM ${table} WHERE ${table === 'villages' ? 'id' : table === 'village_creation_requests' ? 'created_village_id' : 'village_id'}=UNHEX(?) ORDER BY id`, [binaryId(villageId)])
      if (digest(rows) !== frozen.get(table)) throw new Error('MODERATION_UNEXPECTED_BUSINESS_MUTATION')
    }
    const [after] = await db.query('SELECT * FROM village_memberships WHERE village_id=UNHEX(?) ORDER BY id', [binaryId(villageId)])
    if (after.length !== 3) throw new Error('MODERATION_FINAL_MEMBER_BOUNDS_MISMATCH')
    const before = memberBefore as Record<string, unknown>[]
    for (const [index, item] of (after as Record<string, unknown>[]).entries()) {
      const isTarget = Number(item.subject_id) === elder.userId
      if (Number(item.subject_id) === villager.userId) {
        if (item.role !== 'VILLAGER' || BigInt(String(item.version)) !== BigInt(String(before[index].version)) + 2n)
          throw new Error('MODERATION_UI_ROLE_CYCLE_MISMATCH')
        const unchanged = (row: Record<string, unknown>) => Object.fromEntries(Object.entries(row)
          .filter(([k]) => !['updated_at', 'version'].includes(k)))
        if (digest(unchanged(item)) !== digest(unchanged(before[index])))
          throw new Error('MODERATION_UI_ROLE_CYCLE_EXTRA_MUTATION')
        continue
      }
      if (!isTarget) { if (digest(item) !== digest(before[index])) throw new Error('MODERATION_SIBLING_MEMBER_CHANGED'); continue }
      if (item.role !== 'HEADMAN' || item.left_at === null || item.banned_at === null || item.banned_reason !== manifest.banReason)
        throw new Error('MODERATION_BANNED_HEADMAN_NOT_ESTABLISHED')
      if (!(item.left_at instanceof Date) || !(item.banned_at instanceof Date)
        || item.left_at.getTime() !== item.banned_at.getTime()
        || BigInt(String(item.version)) !== BigInt(String(before[index].version)) + 2n)
        throw new Error('MODERATION_TARGET_TRANSITION_MISMATCH')
      const allowed = ['role', 'left_at', 'banned_at', 'banned_reason', 'updated_at', 'version']
      const unchanged = (row: Record<string, unknown>) => Object.fromEntries(Object.entries(row).filter(([k]) => !allowed.includes(k)))
      if (digest(unchanged(item)) !== digest(unchanged(before[index]))) throw new Error('MODERATION_TARGET_EXTRA_MUTATION')
    }
    return { exactMutation: true, targetUserId: elder.userId, role: 'HEADMAN', bannedAtPresent: true, leftAtPresent: true,
      uiRoleCycle: { userId: villager.userId, finalRole: 'VILLAGER', versionIncrement: 2, otherColumnsUnchanged: true },
      initialRoles: ['HEADMAN','ELDER','VILLAGER'], ownedBounds: { users: 3, villages: 1, creationRequests: 1, requests: 2, memberships: 3 } }
  } }
}
