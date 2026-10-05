// 専用GitHub jobだけ。Node22 --experimental-strip-typesで呼ぶSOURCE候補。
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { execFileSync, spawn } from 'node:child_process'
import path from 'node:path'
import { randomBytes } from 'node:crypto'
import { request } from '@playwright/test'
import { prepareOwnedCiFixture, type OwnedCiIdentity } from './village-history-ci-binding.ts'

const root = path.resolve('..') // workflowはfrontendをcwdとして起動する。
const output = path.resolve('build/village-history-real')
mkdirSync(output, { recursive: true })
const safeWrite = (name: string, value: unknown) => writeFileSync(path.join(output, name), JSON.stringify(value, null, 2) + '\n')
const required = (key: string) => { const value = process.env[key]; if (!value) throw new Error(`MISSING_${key}`); return value }
const identity: OwnedCiIdentity = {
  runId: required('GITHUB_RUN_ID'), runAttempt: required('GITHUB_RUN_ATTEMPT'), job: required('GITHUB_JOB'),
  headSha: required('VH_EXPECTED_HEAD'), mysqlServiceId: required('VH_MYSQL_SERVICE_ID'),
  valkeyServiceId: required('VH_VALKEY_SERVICE_ID'), mysqlPort: Number(required('VH_MYSQL_PORT')),
  database: required('VH_DATABASE'), runnerEnvironment: 'github-hosted',
}
const contexts: Awaited<ReturnType<typeof request.newContext>>[] = []
let prepared: Awaited<ReturnType<typeof prepareOwnedCiFixture>> | undefined
let outcome = 'UNRUN_ENV'
let exit = 125
let phase = 'SOURCE_IDENTITY'
let activeUi: ReturnType<typeof spawn> | undefined
let stopRequested = false
let setupLoginSuccesses = 0
let moderationUiLogins: number | null = null
let moderationUiPassed = false
process.once('SIGTERM', () => { stopRequested = true; activeUi?.kill('SIGTERM') })
try {
  if (process.env.GITHUB_ACTIONS !== 'true' || process.env.RUNNER_ENVIRONMENT !== 'github-hosted')
    throw new Error('DEDICATED_GITHUB_HOST_REQUIRED')
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', timeout: 10_000 }).trim()
  if (head !== identity.headSha) throw new Error('SOURCE_HEAD_MISMATCH')
  // 統合後のAPI/UI実在必須。c2829単体ではこのgateが失敗する。
  for (const file of ['frontend/app/pages/my/village-join-requests.vue',
    'frontend/app/composables/village/useVillageJoinRequestHistory.ts']) {
    execFileSync('git', ['cat-file', '-e', `${head}:${file}`], { cwd: root, timeout: 10_000 })
  }
  const controller = execFileSync('git', ['show', `${head}:backend/src/main/java/com/mannschaft/app/village/controller/VillageJoinRequestController.java`],
    { cwd: root, encoding: 'utf8', timeout: 10_000 })
  if (!controller.includes('/api/v1/village-join-requests/me')) throw new Error('INTEGRATED_HISTORY_API_ABSENT')
  const serviceRecords: unknown[] = []
  const verifyServiceIdentity = async (expected: OwnedCiIdentity) => {
    if (expected !== identity) throw new Error('SERVICE_CONTEXT_CHANGED')
    for (const [kind, id] of [['mysql', expected.mysqlServiceId], ['valkey', expected.valkeyServiceId]] as const) {
      const raw = execFileSync('docker', ['inspect', id], { encoding: 'utf8', timeout: 10_000, maxBuffer: 1024 * 1024 })
      const data = JSON.parse(raw)
      if (data.length !== 1 || data[0].Id !== id || data[0].State.Running !== true)
        throw new Error('OFFICIAL_SERVICE_INSPECT_MISMATCH')
      if (kind === 'mysql' && !data[0].NetworkSettings.Ports['3306/tcp']?.some(
        (p: { HostPort: string }) => Number(p.HostPort) === expected.mysqlPort)) throw new Error('OFFICIAL_MYSQL_PORT_MISMATCH')
      serviceRecords.push({ kind, id, image: data[0].Image, created: data[0].Created, running: true })
    }
    safeWrite('service-identity.json', { inputOrigin: 'github-job.services-context', identity, observations: serviceRecords })
    return { mysqlId: expected.mysqlServiceId, valkeyId: expected.valkeyServiceId,
      bothCreatedInThisJob: true as const, oldServicesBorrowed: false as const }
  }
  const ci = readFileSync(path.join(root, 'backend/src/main/resources/application-ci.yml'), 'utf8')
  const ciKey = ci.match(/^    key:\s*([^\r\n]+)$/m)?.[1]?.trim()
  if (!ciKey || !/email:[\s\S]*?simulate: true/.test(ci) || !/email-outbox:[\s\S]*?enabled: false/.test(ci))
    throw new Error('PUBLIC_CI_FAKE_MAIL_KEY_PROFILE_UNPROVEN')
  const runKey = `r${identity.runId}-${identity.runAttempt}`
  const credentials = [0, 1, 2].map((index) => ({ email: `vh-${runKey}-${index}@test.mannschaft.local`,
    password: randomBytes(24).toString('base64url') })) as [{ email: string; password: string }, { email: string; password: string }, { email: string; password: string }]
  const api = required('API_BASE_URL')
  if (api !== 'http://localhost:18080' || required('BASE_URL') !== 'http://localhost:13000') throw new Error('OWNED_ORIGINS_MISMATCH')
  phase = 'SERVICES_DB_FIXTURE_SETUP'
  prepared = await prepareOwnedCiFixture({ identity, verifyServiceIdentity,
    seedPackageJsonPath: path.join(root, 'backend/scripts/package.json'),
    databaseUser: required('VH_DB_USER'), databasePassword: required('VH_DB_PASSWORD'),
    ciEncryptionKeyBase64: ciKey, runKey, credentials,
    persistOwned: async (owned) => safeWrite('owned-fixture.json', {
      // helperがActorを落としたSafeOwnedを渡す。保存境界でも閉じた許可fieldsだけを再構成する。
      villages: owned.villages.map((v) => ({ id: v.id, ownerUserId: v.ownerUserId, slug: v.slug })),
      creationRequests: owned.creationRequests.map((id) => id),
      requests: owned.requests.map((r) => ({ id: r.id, villageId: r.villageId,
        subjectId: r.subjectId, subjectType: r.subjectType, status: r.status,
        createdAt: r.createdAt, message: r.message, reviewComment: r.reviewComment })),
      membershipIds: owned.membershipIds.map((id) => id),
    }),
    authenticateSetupActor: async (userId, index) => {
      const context = await request.newContext({ baseURL: api, storageState: { cookies: [], origins: [] }, timeout: 30_000 })
      contexts.push(context)
      // setup専用API context各1通常login。UIの独立3loginとは合計6。
      const login = await context.post('/api/v1/auth/login', { data: credentials[index] })
      if (login.status() !== 200) throw new Error('SETUP_LOGIN_FAILED')
      const response = await context.get('/api/v1/users/me')
      const me = response.status() === 200 ? (await response.json()).data : undefined
      const cookies = (await context.storageState()).cookies
      if (!me || Number(me.id) !== userId || me.systemRole === 'SYSTEM_ADMIN'
          || !cookies.some((c) => c.name === 'access_token' && c.httpOnly)) throw new Error('SETUP_REAL_PRINCIPAL_FAILED')
      setupLoginSuccesses++
      return { userId, api: context, credentialEnvPrefix: `VH_ACTOR_${index}` }
    },
  })
  safeWrite('baseline.json', prepared.baselineReceipt)
  safeWrite('ui-manifest.json', prepared.uiManifest)
  safeWrite('business-before.json', { approved: true, ...prepared.uiManifest, ownedBounds: { requests: 22, memberships: 12, villages: 6, creationRequests: 6, users: 3 } })
  const childEnv: NodeJS.ProcessEnv = { ...process.env, VILLAGE_HISTORY_FIXTURE_MANIFEST: path.join(output, 'ui-manifest.json'),
    VILLAGE_HISTORY_SAFE_OUTPUT: path.join(output, 'safe-business') }
  credentials.forEach((value, index) => {
    childEnv[`VH_ACTOR_${index}_EMAIL`] = value.email
    childEnv[`VH_ACTOR_${index}_PASSWORD`] = value.password
  })
  phase = 'ACTUAL_UI'
  const child = spawn(process.execPath, ['node_modules/@playwright/test/cli.js', 'test',
    'village-history-real.spec.ts',
    '--config=playwright-village-history.config.ts', '--project=chromium-village-history', '--workers=1', '--retries=0'],
    { cwd: path.resolve('.'), env: childEnv, stdio: 'ignore' })
  activeUi = child
  const code = await new Promise<number>((resolve) => {
    const timer = setTimeout(() => child.kill('SIGTERM'), 270_000)
    child.once('error', () => { clearTimeout(timer); resolve(125) })
    child.once('exit', (value) => { clearTimeout(timer); resolve(value ?? 125) })
  })
  // JSONは失敗message/stackを含む可能性がある。公開用は数値/case名のみへ再構成。
  const actual = JSON.parse(readFileSync(path.join(output, 'actual.json'), 'utf8'))
  const results: { title: string; status: string; retry: number }[] = []
  const walk = (suite: any) => {
    for (const spec of suite.specs ?? []) for (const test of spec.tests ?? [])
      for (const result of test.results ?? []) results.push({ title: spec.title, status: result.status, retry: result.retry })
    for (const nested of suite.suites ?? []) walk(nested)
  }
  for (const suite of actual.suites ?? []) walk(suite)
  const uiCleanup = JSON.parse(readFileSync(path.join(output, 'ui-session-cleanup.json'), 'utf8'))
  const progressPath = path.join(output, 'ui-progress-safe.json')
  const progress = existsSync(progressPath) ? JSON.parse(readFileSync(progressPath, 'utf8')) : null
  const uiLogins = Number.isInteger(progress?.canonicalLoginSuccesses)
    && progress.canonicalLoginSuccesses >= 0 && progress.canonicalLoginSuccesses <= 3
    ? progress.canonicalLoginSuccesses as number : null
  phase = 'BUSINESS_BEFORE_AFTER'
  await prepared.assertBusinessUnchanged()
  safeWrite('business-after.json', { exactBeforeAfter: true, requests: 22, memberships: 12, villages: 6, creationRequests: 6, users: 3 })
  const passed = uiCleanup.uiLogoutSuccesses === 3 && uiCleanup.uiContextsClosed === 3
    && uiLogins === 3 && progress?.phase === 'UI_COMPLETE'
    && !stopRequested && code === 0 && results.length === 1 && results[0].status === 'passed'
    && results[0].retry === 0 && (actual.errors ?? []).length === 0
  safeWrite('ui-actual-safe.json', { processExit: code, results, errors: (actual.errors ?? []).length,
    uiContexts: 3, uiLogins, setupApiContexts: contexts.length, setupLogins: setupLoginSuccesses,
    totalLogins: uiLogins === null ? null : setupLoginSuccesses + uiLogins, passed })
  if (!passed) throw new Error('HISTORY_UI_NOT_PASSED')
  phase = 'MODERATION_FIXTURE_SETUP'
  const extraCredentials = [0, 1, 2].map((index) => ({ email: `vh-${runKey}-moderation-${index}@test.mannschaft.local`,
    password: randomBytes(24).toString('base64url') })) as typeof credentials
  const moderation = await prepared.prepareModeration(extraCredentials, async (userId, index) => {
    const context = await request.newContext({ baseURL: api, storageState: { cookies: [], origins: [] }, timeout: 30_000 })
    contexts.push(context)
    const login = await context.post('/api/v1/auth/login', { data: extraCredentials[index] })
    if (login.status() !== 200) throw new Error('MODERATION_SETUP_LOGIN_FAILED')
    const response = await context.get('/api/v1/users/me')
    const me = response.status() === 200 ? (await response.json()).data : undefined
    const cookies = (await context.storageState()).cookies
    if (!me || Number(me.id) !== userId || me.systemRole === 'SYSTEM_ADMIN'
      || !cookies.some((c) => c.name === 'access_token' && c.httpOnly)) throw new Error('MODERATION_SETUP_PRINCIPAL_FAILED')
    setupLoginSuccesses++
    return { userId, api: context, credentialEnvPrefix: `VH_MODERATION_${index}` }
  })
  safeWrite('moderation-manifest.json', moderation.manifest)
  const moderationEnv: NodeJS.ProcessEnv = { ...childEnv, VH_UI_PHASE: 'moderation',
    VILLAGE_MODERATION_FIXTURE_MANIFEST: path.join(output, 'moderation-manifest.json') }
  extraCredentials.forEach((value, index) => {
    moderationEnv[`VH_MODERATION_${index}_EMAIL`] = value.email
    moderationEnv[`VH_MODERATION_${index}_PASSWORD`] = value.password
  })
  phase = 'MODERATION_ACTUAL_UI'
  const moderationChild = spawn(process.execPath, ['node_modules/@playwright/test/cli.js', 'test',
    'village-moderation-real.spec.ts', '--config=playwright-village-history.config.ts',
    '--project=chromium-village-history', '--workers=1', '--retries=0'],
    { cwd: path.resolve('.'), env: moderationEnv, stdio: 'ignore' })
  activeUi = moderationChild
  const moderationCode = await new Promise<number>((resolve) => {
    const timer = setTimeout(() => moderationChild.kill('SIGTERM'), 270_000)
    moderationChild.once('error', () => { clearTimeout(timer); resolve(125) })
    moderationChild.once('exit', (value) => { clearTimeout(timer); resolve(value ?? 125) })
  })
  const moderationActual = JSON.parse(readFileSync(path.join(output, 'moderation-actual.json'), 'utf8'))
  results.length = 0
  for (const suite of moderationActual.suites ?? []) walk(suite)
  const moderationProgress = JSON.parse(readFileSync(path.join(output, 'moderation-progress-safe.json'), 'utf8'))
  const moderationCleanup = JSON.parse(readFileSync(path.join(output, 'moderation-session-cleanup.json'), 'utf8'))
  moderationUiLogins = Number.isInteger(moderationProgress.canonicalLoginSuccesses)
    && moderationProgress.canonicalLoginSuccesses >= 0 && moderationProgress.canonicalLoginSuccesses <= 3
    ? moderationProgress.canonicalLoginSuccesses : null
  phase = 'MODERATION_BUSINESS_WITNESS'
  safeWrite('moderation-business-proof.json', await moderation.assertFinalBusiness())
  moderationUiPassed = !stopRequested && moderationCode === 0 && results.length === 1
    && results[0].status === 'passed' && results[0].retry === 0 && (moderationActual.errors ?? []).length === 0
    && moderationProgress.phase === 'UI_COMPLETE' && moderationUiLogins === 3
    && moderationCleanup.uiLogoutSuccesses === 3 && moderationCleanup.uiContextsClosed === 3
    && moderationCleanup.observations.every((c: { authCookiesAbsent: boolean }) => c.authCookiesAbsent)
  safeWrite('moderation-actual-safe.json', { processExit: moderationCode, results,
    errors: (moderationActual.errors ?? []).length, uiLogins: moderationUiLogins,
    setupLogins: setupLoginSuccesses, historyUiLogins: uiLogins,
    totalLogins: uiLogins === null || moderationUiLogins === null ? null : setupLoginSuccesses + uiLogins + moderationUiLogins,
    passed: moderationUiPassed, autonomousExploration: 'HOLD' })
  outcome = moderationUiPassed ? 'FIXED_UI_PASS_PENDING_CLEANUP' : 'ACTUAL_UI_FAILURE'
  exit = moderationUiPassed ? 0 : 1
} catch (error) {
  outcome = 'ENV_OR_FIXTURE_OR_UI_UNPROVEN'
  const reason = error instanceof Error && /^[A-Z0-9_]+$/.test(error.message) ? error.message : 'PRIVATE_ERROR_NOT_PUBLISHED'
  safeWrite('failure-safe.json', { phase, reason })
  exit = 125
} finally {
  let fixtureCleanup = true
  let setupLogoutSuccesses = 0
  let setupContextsDisposed = 0
  const setupLogoutObservations: { index: number; status: number | null; authCookiesAbsent: boolean }[] = []
  let databaseClosed: boolean | null = null
  for (const [index, context] of contexts.entries()) {
    let status: number | null = null
    let authCookiesAbsent = false
    try {
      status = (await context.post('/api/v1/auth/logout')).status()
      if (status !== 200) fixtureCleanup = false
      else setupLogoutSuccesses++
    }
    catch { fixtureCleanup = false }
    try { authCookiesAbsent = !(await context.storageState()).cookies.some((c) => ['access_token', 'refresh_token'].includes(c.name)) }
    catch { fixtureCleanup = false }
    if (!authCookiesAbsent) fixtureCleanup = false
    setupLogoutObservations.push({ index, status, authCookiesAbsent })
    try { await context.dispose(); setupContextsDisposed++ } catch { fixtureCleanup = false }
  }
  try { if (prepared) { await prepared.closeDatabase(); databaseClosed = true } }
  catch { databaseClosed = false; fixtureCleanup = false }
  fixtureCleanup = fixtureCleanup && contexts.length === 6 && setupLoginSuccesses === 6
    && setupLogoutSuccesses === 6 && setupContextsDisposed === 6
  if (!fixtureCleanup) exit = 125
  safeWrite('fixture-completion.json', { identity, outcome, exit, setupLogoutSuccesses, setupContextsDisposed,
    setupLoginSuccesses, moderationUiLogins, moderationUiPassed,
    setupSessionsCleanupConfirmed: fixtureCleanup,
    setupLogoutObservations,
    databaseClosed,
    runtimeCleanup: 'PENDING_OWNED_SUPERVISOR', servicesCleanup: 'PENDING_PLATFORM_CLEANUP', finalCleanup: 'UNPROVEN' })
}
process.exitCode = exit
