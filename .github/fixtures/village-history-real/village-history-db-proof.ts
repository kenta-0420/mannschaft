// SOURCE候補のみ。既存mysql2接続を注入する。接続/DDL/DELETEは提供しない。
import { createHash } from 'node:crypto'
import type { Actor, Owned, SeedDb, ExactDbProof } from './village-history-fixture-helper.ts'

const hex = (id: string) => {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id))
    throw new Error('OWNED_UUID_INVALID')
  return id.replaceAll('-', '').toUpperCase()
}
const digest = (value: unknown) => createHash('sha256').update(JSON.stringify(value)).digest('hex')
type DbRow = Record<string, unknown>
const sentinelSql = 'SELECT id,status,is_searchable,locale,timezone,deleted_at,created_at,updated_at '
  + 'FROM users WHERE id IN (?,?) ORDER BY id'

export async function captureFreshUserBaseline(db: SeedDb, assertNewCiServices: () => Promise<void>) {
  await assertNewCiServices()
  const [users] = await db.query('SELECT id,status,CASE WHEN SHA2(email,256)=SHA2(?,256) THEN \'SYSTEM_SEED\' '
    + 'WHEN SHA2(email,256)=SHA2(?,256) THEN \'DELETED_SEED\' ELSE \'UNKNOWN\' END AS seedIdentity '
    + 'FROM users ORDER BY id', ['system@mannschaft.local', 'deleted@system.internal'])
  const rows = users as DbRow[]
  const system = rows.filter((r) => r.seedIdentity === 'SYSTEM_SEED' && Number(r.id) === 1 && r.status === 'ACTIVE')
  const deleted = rows.filter((r) => r.seedIdentity === 'DELETED_SEED' && r.status === 'ARCHIVED')
  if (rows.length !== 2 || system.length !== 1 || deleted.length !== 1
      || !Number.isSafeInteger(Number(deleted[0].id)) || Number(deleted[0].id) < 0
      || Number(deleted[0].id) === 1) throw new Error('FRESH_SENTINELS_NOT_EXACT')
  const ids = rows.map((r) => Number(r.id))
  const [mode] = await db.query('SELECT @@SESSION.sql_mode AS sqlMode', [])
  if ((mode as DbRow[]).length !== 1 || typeof (mode as DbRow[])[0]?.sqlMode !== 'string')
    throw new Error('SESSION_SQL_MODE_UNPROVEN')
  const [history] = await db.query('SELECT installed_rank,version,script,success FROM flyway_schema_history '
    + 'WHERE script IN (?,?) ORDER BY installed_rank',
    ['V1.012__seed_system_user.sql', 'V12.004__seed_deleted_sentinel_user.sql'])
  const migrations = history as DbRow[]
  if (migrations.length !== 2 || migrations[0].script !== 'V1.012__seed_system_user.sql'
      || migrations[1].script !== 'V12.004__seed_deleted_sentinel_user.sql'
      || migrations.some((m) => Number(m.success) !== 1)) throw new Error('SENTINEL_MIGRATION_ORDER_UNPROVEN')
  // 安全なprojectionのみ。password_hash/email/PII暗号列は取得しない。
  // id0 INSERTの実採番を取得する。NO_AUTO_VALUE_ON_ZEROの設定変更はしない。
  return { ids, sessionSqlMode: (mode as DbRow[])[0]?.sqlMode, migrations,
    safeProjectionSha256: digest((await db.query(sentinelSql, ids))[0]),
    rolesSha256: digest((await db.query('SELECT * FROM user_roles ORDER BY id', []))[0]) }
}

// assertNewCiServicesは将来の専用job起動/空services一次receiptで実装する。
// 環境変数のtrueだけで既DBをfreshと認定する代用品は不可。
export function createHistoryDbProof(db: SeedDb, userIds: [number, number, number],
  assertNewCiServices: () => Promise<void>, baseline: Awaited<ReturnType<typeof captureFreshUserBaseline>>): ExactDbProof {
  let approvedSnapshot: string | undefined
  let oldUserSnapshot: string | undefined
  let roleSnapshot: string | undefined
  const read = async (sql: string, params: unknown[] = []) => (await db.query(sql, params))[0] as DbRow[]
  const snapshot = async () => ({
    villages: await read('SELECT * FROM villages ORDER BY id'),
    memberships: await read('SELECT * FROM village_memberships ORDER BY id'),
    creationRequests: await read('SELECT * FROM village_creation_requests ORDER BY id'),
    requests: await read('SELECT * FROM village_join_requests ORDER BY id'),
  })
  return {
    async assertFreshOwnedDatabase() {
      await assertNewCiServices()
      for (const table of ['villages', 'village_memberships', 'village_creation_requests', 'village_join_requests']) {
        const rows = await read(`SELECT COUNT(*) AS n FROM ${table}`)
        if (Number(rows[0]?.n) !== 0) throw new Error('FRESH_BUSINESS_COLLECTION_NOT_EMPTY')
      }
      // setup前のみ。既user/rolesを借用しない。新fake3の事前作成は別単一TX。
      const roles = await read('SELECT id FROM user_roles WHERE user_id IN (?,?,?)', userIds)
      if (roles.length !== 0) throw new Error('FAKE_ACTOR_HAS_SYSTEM_OR_SCOPE_ROLE')
      const users = await read('SELECT id,status FROM users WHERE id IN (?,?,?) ORDER BY id', userIds)
      if (users.length !== 3 || users.some((u) => u.status !== 'ACTIVE'))
        throw new Error('FAKE_ACTORS_NOT_EXACT_ACTIVE_THREE')
      const oldUsers = await read('SELECT id,status FROM users WHERE id NOT IN (?,?,?) ORDER BY id', userIds)
      // V12.004/V1.012の既定2sentinelは借用しない。新fake3のdeltaのみ所有する。
      if (oldUsers.length !== 2 || oldUsers.map((r) => Number(r.id)).join(',') !== baseline.ids.join(',')
          || userIds.some((id) => !Number.isSafeInteger(id) || id <= 0 || baseline.ids.includes(id))
          || new Set(userIds).size !== 3)
        throw new Error('UNEXPECTED_PREEXISTING_ACTOR')
      oldUserSnapshot = digest(await read(sentinelSql, baseline.ids))
      roleSnapshot = digest(await read('SELECT * FROM user_roles ORDER BY id'))
      if (oldUserSnapshot !== baseline.safeProjectionSha256 || roleSnapshot !== baseline.rolesSha256)
        throw new Error('FIXTURE_SEED_CHANGED_SENTINELS_OR_ROLES')
    },
    async assertIdentifierAbsent(slug) {
      const rows = await read('SELECT id FROM villages WHERE slug=? UNION ALL '
        + 'SELECT id FROM village_creation_requests WHERE proposed_slug=?', [slug, slug])
      if (rows.length !== 0) throw new Error('FAKE_SLUG_COLLISION')
    },
    async captureAndVerifyOwned(actors: Actor[], owned: Owned) {
      await assertNewCiServices()
      if (actors.length !== 3 || actors.some((a, i) => a.userId !== userIds[i])
          || owned.villages.length !== 6 || owned.requests.length !== 22
          || owned.creationRequests.length !== 6 || owned.membershipIds.length !== 12)
        throw new Error('OWNED_BOUND_MISMATCH')
      const villages = await read('SELECT HEX(id) AS id,slug,created_by_user_id AS owner,deleted_at,archived_at '
        + 'FROM villages ORDER BY id')
      if (villages.length !== 6 || villages.some((v) => !owned.villages.some((o) =>
        hex(o.id) === v.id && o.slug === v.slug && Number(v.owner) === o.owner.userId)
        || v.deleted_at !== null || v.archived_at !== null)) throw new Error('VILLAGE_OWNER_SET_MISMATCH')
      const requests = await read('SELECT HEX(id) AS id,HEX(village_id) AS villageId,requester_user_id AS requester,'
        + 'subject_type AS subjectType,subject_id AS subjectId,status,message,review_comment AS reviewComment,'
        + 'created_at AS createdAt FROM village_join_requests ORDER BY created_at DESC,id DESC')
      if (requests.length !== 22 || requests.some((r) => !owned.requests.some((o) => hex(o.id) === r.id
        && hex(o.villageId) === r.villageId
        && o.subjectId === Number(r.subjectId) && o.status === r.status && o.message === r.message
        && o.reviewComment === r.reviewComment) || r.subjectType !== 'USER'
        || Number(r.requester) !== Number(r.subjectId)
        || !owned.villages.some((v) => hex(v.id) === r.villageId))) throw new Error('REQUEST_SET_MISMATCH')
      const memberships = await read('SELECT HEX(id) AS id,HEX(village_id) AS villageId,subject_type AS subjectType,'
        + 'subject_id AS subjectId,role,left_at AS leftAt,banned_at AS bannedAt FROM village_memberships ORDER BY id')
      if (memberships.length !== 12 || memberships.some((m) => m.subjectType !== 'USER'
        || m.bannedAt !== null || !owned.membershipIds.some((id) => hex(id) === m.id)
        || !owned.villages.some((v) => hex(v.id) === m.villageId)))
        throw new Error('MEMBERSHIP_SET_MISMATCH')
      for (const village of owned.villages) {
        const rows = memberships.filter((m) => m.villageId === hex(village.id))
        if (rows.length !== 2 || rows.filter((m) => Number(m.subjectId) === village.owner.userId
          && m.role === 'HEADMAN' && m.leftAt === null).length !== 1
          || rows.filter((m) => Number(m.subjectId) === userIds[0] && m.role === 'VILLAGER'
          && m.leftAt !== null && owned.membershipIds.some((id) => hex(id) === m.id)).length !== 1)
          throw new Error('ACTUAL_ROLE_OR_LEAVE_MISMATCH')
      }
      const creations = await read('SELECT HEX(id) AS id,HEX(created_village_id) AS villageId,'
        + 'requester_user_id AS owner,status FROM village_creation_requests ORDER BY id')
      if (creations.length !== 6 || creations.some((r) => r.status !== 'APPROVED'
        || !owned.creationRequests.some((id) => hex(id) === r.id)
        || !owned.villages.some((v) => hex(v.id) === r.villageId && v.owner.userId === Number(r.owner))))
        throw new Error('CREATION_OWNER_SET_MISMATCH')
      const ordered = userIds.map((id) => requests.filter((r) => Number(r.requester) === id).map((r) => r.id))
      if (ordered.map((ids) => ids.length).join(',') !== '21,1,0') throw new Error('HISTORY_COUNT_MISMATCH')
      const lastApproved = owned.requests.filter((r) => r.subjectId === userIds[0] && r.status === 'APPROVED').at(-1)
      if (!lastApproved || !ordered[0].slice(0, 20).includes(hex(lastApproved.id)))
        throw new Error('LEFT_APPROVED_NOT_IN_FIRST_PAGE')
      approvedSnapshot = digest(await snapshot())
      // ID/status/roleのみ。資格情報やDB接続情報は返さない。
      return { orderedIdsHex: ordered, memberships, leftVillageRequestId: lastApproved.id,
        ownedBounds: { users: 3, villages: 6, memberships: 12, creationRequests: 6, requests: 22 },
        businessSnapshotSha256: approvedSnapshot }
    },
    async assertBusinessUnchanged() {
      await assertNewCiServices()
      if (!approvedSnapshot || digest(await snapshot()) !== approvedSnapshot)
        throw new Error('UI_MUTATED_OR_SNAPSHOT_UNPROVEN')
      const nonOwnedIds = await read('SELECT id FROM users WHERE id NOT IN (?,?,?) ORDER BY id', userIds)
      if (nonOwnedIds.map((r) => Number(r.id)).join(',') !== baseline.ids.join(',')) throw new Error('UNKNOWN_ACTOR_DELTA')
      if (!oldUserSnapshot || digest(await read(sentinelSql, baseline.ids))
          !== oldUserSnapshot || !roleSnapshot || digest(await read('SELECT * FROM user_roles ORDER BY id')) !== roleSnapshot)
        throw new Error('OLD_USER_OR_ROLE_COLLECTION_CHANGED')
    },
  }
}
