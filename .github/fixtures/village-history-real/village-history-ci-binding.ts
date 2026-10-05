// 新workflow未設置。呼出なしSOURCE候補。秘密値は入力メモリだけ、receiptは下記safe型だけ。
import { createRequire } from 'node:module'
import { createCipheriv, randomBytes } from 'node:crypto'
import { seedThreeFakeUsers, setupHistoryFixture, type Actor, type SeedDb } from './village-history-fixture-helper.ts'
import { captureFreshUserBaseline, createHistoryDbProof } from './village-history-db-proof.ts'
import { assembleUiManifest } from './village-history-manifest.ts'
import { captureHistoryBoundary, setupModerationFixture } from './village-moderation-fixture.ts'

export type OwnedCiIdentity = {
  runId: string; runAttempt: string; job: string; headSha: string
  mysqlServiceId: string; valkeyServiceId: string; mysqlPort: number
  database: string; runnerEnvironment: 'github-hosted'
}
type VerifiedServices = {
  mysqlId: string; valkeyId: string; bothCreatedInThisJob: true; oldServicesBorrowed: false
}
export type SafeCleanupReceipt = {
  identity: OwnedCiIdentity
  fixtureCleanup: 'COMPLETE' | 'UNPROVEN'
  browserContextsClosed: boolean; ownedProcessesRemaining: boolean
  servicesCleanup: 'PENDING_PLATFORM_CLEANUP'
  finalCleanup: 'UNPROVEN'
}

export async function prepareOwnedCiFixture(input: {
  identity: OwnedCiIdentity
  // 外側専用jobの実docker inspect/起動receiptに由来。単なるenv approved=trueは禁止。
  verifyServiceIdentity: (identity: OwnedCiIdentity) => Promise<VerifiedServices>
  // backend/scripts/package.jsonの実require入口。新packageや全seed scriptは実行しない。
  seedPackageJsonPath: string
  databaseUser: string; databasePassword: string
  ciEncryptionKeyBase64: string
  runKey: string
  credentials: [{ email: string; password: string }, { email: string; password: string }, { email: string; password: string }]
  authenticateSetupActor: (userId: number, index: number) => Promise<Actor>
  persistOwned: Parameters<typeof setupHistoryFixture>[3]
}) {
  const i = input.identity
  if (i.runnerEnvironment !== 'github-hosted' || !/^\d+$/.test(i.runId) || !/^\d+$/.test(i.runAttempt)
      || !/^[a-zA-Z0-9_-]+$/.test(i.job) || !/^[0-9a-f]{40}$/.test(i.headSha)
      || !/^[0-9a-f]{64}$/.test(i.mysqlServiceId) || !/^[0-9a-f]{64}$/.test(i.valkeyServiceId)
      || i.mysqlServiceId === i.valkeyServiceId || !Number.isInteger(i.mysqlPort) || i.mysqlPort < 1024 || i.mysqlPort > 65535
      || !/^vh_[a-z0-9_]{8,50}$/.test(i.database)) throw new Error('DEDICATED_JOB_IDENTITY_INVALID')
  const assertNewCiServices = async () => {
    const observed = await input.verifyServiceIdentity(i)
    if (observed.mysqlId !== i.mysqlServiceId || observed.valkeyId !== i.valkeyServiceId
        || observed.bothCreatedInThisJob !== true || observed.oldServicesBorrowed !== false)
      throw new Error('DEDICATED_SERVICE_PROVENANCE_UNPROVEN')
  }
  await assertNewCiServices()
  const requireSeed = createRequire(input.seedPackageJsonPath)
  // backend/scripts/package-lock.json既存version。新依存取得をこのhelperは行わない。
  if (requireSeed('bcryptjs/package.json').version !== '2.4.3'
      || requireSeed('mysql2/package.json').version !== '3.22.5') throw new Error('SEED_RUNTIME_VERSION_MISMATCH')
  const bcrypt = requireSeed('bcryptjs') as { hashSync(value: string, rounds: number): string }
  type RawDb = {
    query(options: { sql: string; timeout: number }, params: unknown[]): Promise<[unknown[], unknown]>
    execute(options: { sql: string; timeout: number }, params: unknown[]): ReturnType<SeedDb['execute']>
    end(): Promise<void>
  }
  const mysql = requireSeed('mysql2/promise') as { createConnection(options: unknown): Promise<RawDb> }
  const key = Buffer.from(input.ciEncryptionKeyBase64, 'base64')
  if (key.length !== 32) throw new Error('CI_FAKE_ENCRYPTION_KEY_INVALID')
  const encryptName = (value: string) => {
    const iv = randomBytes(12)
    const cipher = createCipheriv('aes-256-gcm', key, iv)
    return Buffer.concat([iv, cipher.update(value, 'utf8'), cipher.final(), cipher.getAuthTag()]).toString('base64')
  }
  // seed-e2e-data.js:66と同一の既存bcryptjs8/$2a$形式。
  const hashPassword = async (password: string) => bcrypt.hashSync(password, 8).replace('$2b$', '$2a$')
  const raw = await mysql.createConnection({ host: '127.0.0.1', port: i.mysqlPort,
    user: input.databaseUser, password: input.databasePassword, database: i.database,
    connectTimeout: 10_000, supportBigNumbers: true, bigNumberStrings: true, timezone: 'Z',
    multipleStatements: false })
  const db: SeedDb & { end(): Promise<void> } = {
    query: (sql, params) => raw.query({ sql, timeout: 10_000 }, params),
    execute: (sql, params) => raw.execute({ sql, timeout: 10_000 }, params),
    beginTransaction: async () => { await raw.query({ sql: 'START TRANSACTION', timeout: 10_000 }, []) },
    commit: async () => { await raw.query({ sql: 'COMMIT', timeout: 10_000 }, []) },
    rollback: async () => { await raw.query({ sql: 'ROLLBACK', timeout: 10_000 }, []) },
    end: () => raw.end(),
  }
  try {
    const [actual] = await db.query('SELECT DATABASE() AS db', [])
    if ((actual as { db: string }[]).length !== 1 || (actual as { db: string }[])[0].db !== i.database)
      throw new Error('ACTUAL_DATABASE_IDENTITY_MISMATCH')
    const baseline = await captureFreshUserBaseline(db, assertNewCiServices)
    // village系はfake3作成前にも空であることを確認する。
    for (const table of ['villages', 'village_memberships', 'village_creation_requests', 'village_join_requests']) {
      const [rows] = await db.query(`SELECT COUNT(*) AS n FROM ${table}`, [])
      if (Number((rows as { n: number }[])[0]?.n) !== 0) throw new Error('PRESEED_BUSINESS_NOT_EMPTY')
    }
    const ids = await seedThreeFakeUsers(db, input.credentials, hashPassword, encryptName)
    const actors: [Actor, Actor, Actor] = [await input.authenticateSetupActor(ids[0], 0),
      await input.authenticateSetupActor(ids[1], 1), await input.authenticateSetupActor(ids[2], 2)]
    const proof = createHistoryDbProof(db, ids as [number, number, number], assertNewCiServices, baseline)
    const setup = await setupHistoryFixture(actors, proof, input.runKey, input.persistOwned)
    const uiManifest = assembleUiManifest(actors, setup.owned, setup.proof)
    // UI後のwitness/cleanupまで同接続とproofを所有する。秘密値/接続はJSONへ出さない。
    return { actors, owned: setup.owned, uiManifest, assertBusinessUnchanged: () => proof.assertBusinessUnchanged(setup.owned),
      // 履歴UIの不変確認を呼出側で終えてから追加する。旧22/12集合を別境界で固定する。
      prepareModeration: async (credentials: typeof input.credentials,
        authenticate: (id: number, index: number) => Promise<Actor>) => {
        await proof.assertBusinessUnchanged(setup.owned)
        const historyBoundary = await captureHistoryBoundary(db)
        const extraKey = Buffer.from(input.ciEncryptionKeyBase64, 'base64')
        try {
          if (extraKey.length !== 32) throw new Error('CI_FAKE_ENCRYPTION_KEY_INVALID')
          const encrypt = (value: string) => {
            const iv = randomBytes(12)
            const cipher = createCipheriv('aes-256-gcm', extraKey, iv)
            return Buffer.concat([iv, cipher.update(value, 'utf8'), cipher.final(), cipher.getAuthTag()]).toString('base64')
          }
          const extraIds = await seedThreeFakeUsers(db, credentials, hashPassword, encrypt)
          const extraActors: [Actor, Actor, Actor] = [await authenticate(extraIds[0], 0),
            await authenticate(extraIds[1], 1), await authenticate(extraIds[2], 2)]
          const fixture = await setupModerationFixture(db, extraActors, input.runKey)
          return { manifest: fixture.manifest, assertFinalBusiness: async () => {
            await assertNewCiServices()
            const history = await historyBoundary(fixture.manifest, extraIds)
            const mutation = await fixture.assertExpectedMutation()
            return { history, mutation }
          } }
        } finally { extraKey.fill(0) }
      },
      proof, closeDatabase: () => db.end(), safeIdentity: i,
      baselineReceipt: { ids: baseline.ids, safeProjectionSha256: baseline.safeProjectionSha256,
        rolesSha256: baseline.rolesSha256, sessionSqlMode: baseline.sessionSqlMode, migrations: baseline.migrations } }
  } catch {
    await db.end()
    throw new Error('DEDICATED_FIXTURE_PREPARATION_FAILED')
  } finally {
    key.fill(0)
  }
}
