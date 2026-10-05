// 候補のみ。新owned fresh DB・通常fake3本人・最終sourceを殿がGoした後に限る。
import type { APIRequestContext } from '@playwright/test'

export type Actor = { userId: number; api: APIRequestContext; credentialEnvPrefix: string }
export type Row = { id: string; villageId: string; subjectId: number; subjectType: 'USER'; status: string;
  createdAt: string; message: string; reviewComment: string | null }
export type Owned = { villages: { id: string; owner: Actor; slug: string }[];
  creationRequests: string[]; requests: Row[]; membershipIds: string[] }
type SafeOwned = { villages: { id: string; ownerUserId: number; slug: string }[];
  creationRequests: string[]; requests: Row[]; membershipIds: string[] }
function safeOwned(owned: Owned): SafeOwned {
  return { villages: owned.villages.map((v) => ({ id: v.id, ownerUserId: v.owner.userId, slug: v.slug })),
    creationRequests: [...owned.creationRequests], requests: owned.requests.map((r) => ({ ...r })),
    membershipIds: [...owned.membershipIds] }
}
export type ExactDbProof = {
  assertFreshOwnedDatabase(): Promise<void>
  assertIdentifierAbsent(slug: string): Promise<void>
  // SELECTのみ。exact ownedID/親一致、旧集合非所属、role/left_at/banned_at実値を返す。
  captureAndVerifyOwned(actors: Actor[], owned: Owned): Promise<unknown>
  assertBusinessUnchanged(owned: Owned): Promise<void>
}
export type SeedDb = {
  beginTransaction(): Promise<void>; commit(): Promise<void>; rollback(): Promise<void>
  execute(sql: string, params: unknown[]): Promise<[{ insertId: number; affectedRows: number }, unknown]>
  query(sql: string, params: unknown[]): Promise<[unknown[], unknown]>
}

// CMP1017 canonical run-attempt1.cjs:27–29/HashPassword.javaの新actor限定構築を再利用する候補。
// hashPasswordは既存Spring BCrypt gensalt8/$2a$ CLIのstdoutを内部捕捉する。
// encryptNameは同ownedCI鍵のAES-GCM(12byte IV + ciphertext + 16byte tag)。値をログに出さない。
export async function seedThreeFakeUsers(db: SeedDb, users: [
  { email: string; password: string }, { email: string; password: string }, { email: string; password: string }
], hashPassword: (password: string) => Promise<string>, encryptName: (name: string) => string) {
  // 呼出前: owned freshDB/旧集合保存/外部メールsimulate/送信無効/source型確認が必須。
  const ids: number[] = []
  await db.beginTransaction()
  try {
    for (let index = 0; index < 3; index++) {
      const user = users[index]
      if (!/^vh-[a-z0-9-]+@test\.mannschaft\.local$/.test(user.email)) throw new Error('FAKE_IDENTIFIER_INVALID')
      const [existing] = await db.query('SELECT id FROM users WHERE email = ?', [user.email])
      if (existing.length !== 0) throw new Error('FAKE_IDENTIFIER_COLLISION')
      const hash = await hashPassword(user.password)
      if (!hash.startsWith('$2a$08$')) throw new Error('CANONICAL_PASSWORD_FORMAT_INVALID')
      const [result] = await db.execute('INSERT INTO users '
        + '(email,password_hash,last_name,first_name,display_name,is_searchable,encryption_key_version,locale,timezone,status,reporting_restricted,created_at,updated_at) '
        + "VALUES(?,?,?,?,?,1,1,'ja','Asia/Tokyo','ACTIVE',0,NOW(),NOW())",
        [user.email, hash, encryptName('履歴実機'), encryptName(String(index)), `履歴実機${index}`])
      if (result.affectedRows !== 1 || result.insertId <= 0) throw new Error('FAKE_INSERT_NOT_EXACT_ONE')
      ids.push(result.insertId)
    }
    await db.commit()
    return ids // receiptは実IDのみ、email/hash/password/暗号値は保存しない。
  } catch {
    await db.rollback()
    throw new Error('FAKE_USER_SETUP_FAILED')
  }
}

async function call<T>(actor: Actor, path: string, method: 'POST' | 'PATCH' | 'DELETE',
  data?: unknown, expectedStatus = 200): Promise<T> {
  const response = await actor.api.fetch(path, { method, data })
  if (response.status() !== expectedStatus) throw new Error(`SETUP_${method}_HTTP_${response.status()}`)
  // 応答全体や資格情報をログに出さない。
  return expectedStatus === 204 ? undefined as T : (await response.json()).data as T
}

async function assertPrincipal(actor: Actor, expectedAdmin: boolean) {
  const response = await actor.api.get('/api/v1/users/me')
  if (response.status() !== 200) throw new Error('SETUP_PRINCIPAL_FAILED')
  const me = (await response.json()).data as { id: number; systemRole: string | null }
  if (me.id !== actor.userId || (me.systemRole === 'SYSTEM_ADMIN') !== expectedAdmin)
    throw new Error('SETUP_PRINCIPAL_MISMATCH')
}

// 新規CI services専用候補。既DBへの接続・seed・物理DELETEを提供しない。
export async function setupHistoryFixture(actors: [Actor, Actor, Actor],
  db: ExactDbProof, runKey: string, persistSafe: (owned: SafeOwned) => Promise<void>) {
  if (!/^[a-z0-9-]{8,24}$/.test(runKey)) throw new Error('RUN_KEY_INVALID')
  await db.assertFreshOwnedDatabase()
  for (const actor of actors) await assertPrincipal(actor, false)
  const [applicant, other, headman] = actors
  const owned: Owned = { villages: [], creationRequests: [], requests: [], membershipIds: [] }
  const persist = () => persistSafe(safeOwned(owned))
  // C3+B3＝創村日率各3以内。最後のC村をX、B村の一つをYとする。
  const owners = [other, other, other, headman, headman, headman]
  for (const [index, owner] of owners.entries()) {
    const slug = `vh-${runKey}-${index}`
    await db.assertIdentifierAbsent(slug)
    const response = await call<{ id: string; status: string; createdVillageId: string }>(owner,
      '/api/v1/villages/creation-requests', 'POST', {
        name: slug, slug, purpose: '専用入村履歴実機fixture', type: 'COMMUNITY',
        guidelineAgreedAt: new Date().toISOString(),
      }, 201)
    // 現正本は同TX自動承認。運営approveの再呼出・SYS代用をしない。
    owned.creationRequests.push(response.id)
    if (response.createdVillageId) owned.villages.push({ id: response.createdVillageId, owner, slug })
    await persist()
    if (response.status !== 'APPROVED' || !response.createdVillageId)
      throw new Error('AUTO_APPROVAL_NOT_ESTABLISHED')
    const members = await owner.api.get(`/api/v1/villages/${response.createdVillageId}/memberships?page=0&size=50`)
    if (members.status() !== 200) throw new Error('HEADMAN_MEMBERSHIP_READ_FAILED')
    const content = (await members.json()).data.content as {
      id: string; subjectId: number; subjectType: string; role: string
    }[]
    const headmen = content.filter((m) => m.subjectType === 'USER' && m.subjectId === owner.userId && m.role === 'HEADMAN')
    if (content.length !== 1 || headmen.length !== 1) throw new Error('HEADMAN_MEMBERSHIP_NOT_EXACT_ONE')
    owned.membershipIds.push(headmen[0].id)
    await persist()
    await call(owner, `/api/v1/villages/${response.createdVillageId}`, 'PATCH', { joinPolicy: 'APPROVAL' })
  }
  async function submit(village: Owned['villages'][number], suffix: string) {
    const row = await call<Row>(applicant, `/api/v1/villages/${village.id}/join-requests`, 'POST', {
      subjectType: 'USER', subjectId: applicant.userId, message: `${runKey}-${suffix}`,
    }, 201)
    owned.requests.push(row)
    await persist()
    if (row.villageId !== village.id || row.subjectId !== applicant.userId
        || row.subjectType !== 'USER' || row.status !== 'PENDING')
      throw new Error('REQUEST_OWNER_STATUS_MISMATCH')
    return row
  }
  async function finish(village: Owned['villages'][number], row: Row,
    action: 'withdraw' | 'reject' | 'approve', status: string) {
    const actor = action === 'withdraw' ? applicant : village.owner
    const result = await call<Row>(actor,
      `/api/v1/villages/${village.id}/join-requests/${row.id}/${action}`, 'POST',
      action === 'withdraw' ? undefined : { reviewComment: `${runKey}-${action}-comment` })
    if (result.id !== row.id || result.villageId !== village.id || result.villageId !== row.villageId
        || result.subjectId !== applicant.userId || result.subjectType !== 'USER' || result.status !== status)
      throw new Error('REQUEST_TRANSITION_MISMATCH')
    Object.assign(row, result)
    await persist()
  }
  async function leaveApproved(village: Owned['villages'][number]) {
    const response = await village.owner.api.get(`/api/v1/villages/${village.id}/memberships?page=0&size=50`)
    if (response.status() !== 200) throw new Error('MEMBERSHIP_READ_FAILED')
    const content = (await response.json()).data.content as { id: string; subjectId: number; subjectType: string }[]
    const matches = content.filter((m) => m.subjectType === 'USER' && m.subjectId === applicant.userId)
    if (matches.length !== 1) throw new Error('MEMBERSHIP_NOT_EXACT_ONE')
    owned.membershipIds.push(matches[0].id)
    await persist()
    await call(applicant, `/api/v1/villages/${village.id}/memberships/${matches[0].id}`, 'DELETE', undefined, 204)
  }
  for (const [index, village] of owned.villages.entries()) {
    if (index < 5) {
      await finish(village, await submit(village, `${index}-withdraw`), 'withdraw', 'WITHDRAWN')
      await finish(village, await submit(village, `${index}-reject`), 'reject', 'REJECTED')
    }
    await finish(village, await submit(village, `${index}-approve`), 'approve', 'APPROVED')
    await leaveApproved(village)
    if (index < 5) await submit(village, `${index}-pending`)
  }
  const target = owned.villages[5]
  const pending = await call<Row>(other, `/api/v1/villages/${target.id}/join-requests`, 'POST', {
    subjectType: 'USER', subjectId: other.userId, message: `${runKey}-other-pending`,
  }, 201)
  owned.requests.push(pending)
  await persist()
  if (pending.villageId !== target.id || pending.subjectId !== other.userId
      || pending.subjectType !== 'USER' || pending.status !== 'PENDING'
      || owned.requests.length !== 22 || owned.villages.length !== 6
      || owned.membershipIds.length !== 12 || new Set(owned.membershipIds).size !== 12
      || new Set(owned.requests.map((r) => r.id)).size !== 22)
    throw new Error('FIXTURE_BOUNDS_MISMATCH')
  const proof = await db.captureAndVerifyOwned(actors, owned)
  return { owned, proof }
}

export async function cleanupHistoryFixture(actors: [Actor, Actor, Actor], owned: Owned, db: ExactDbProof,
  shutdownOwnedRuntime: () => Promise<{ browserContextsClosed: true; ownedProcessesRemaining: false }>) {
  // UI後、cleanup書込み前の業務不変が成立して初めて終了手順へ進む。
  let businessUnchanged = false
  try { await db.assertBusinessUnchanged(owned); businessUnchanged = true } catch { /* 不変未証明でも所有終了を続行 */ }
  // Aの5PENDINGは既WITHDRAWN等と一意衝突するため遷移させない。
  // BだけのPENDINGもここでは変更不要。新規CI servicesの終了で全fixtureを破棄する。
  let setupLogoutSuccesses = 0
  let setupAuthCookiesAbsent = 0
  for (const actor of actors) {
    // 正準logoutは200 EMPTY。JSONをparseしない。
    try { if ((await actor.api.post('/api/v1/auth/logout')).status() === 200) setupLogoutSuccesses++ } catch { /* 全3を試す */ }
    try {
      if (!(await actor.api.storageState()).cookies.some((c) => ['access_token', 'refresh_token'].includes(c.name)))
        setupAuthCookiesAbsent++
    } catch { /* 値非公開、未証明として残す */ }
  }
  let closed = { browserContextsClosed: false, ownedProcessesRemaining: true }
  try { closed = await shutdownOwnedRuntime() } catch { /* 未確認として返す */ }
  // job.servicesの破棄はartifact提出後にplatformが行う。ここでは真を捏造しない。
  const complete = businessUnchanged && setupLogoutSuccesses === 3 && setupAuthCookiesAbsent === 3
    && closed.browserContextsClosed && !closed.ownedProcessesRemaining
  return { fixtureCleanup: complete ? 'COMPLETE' : 'UNPROVEN', businessUnchanged, setupLogoutSuccesses,
    setupAuthCookiesAbsent,
    ...closed,
    servicesCleanup: 'PENDING_PLATFORM_CLEANUP', finalCleanup: 'UNPROVEN' } as const
}
