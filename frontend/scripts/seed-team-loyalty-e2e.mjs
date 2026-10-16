/** 専用DBだけへ本戦役の最小actorを追加する。共有DB・既存actorは変更しない。 */
import { createRequire } from 'node:module'
import { randomBytes, createCipheriv } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const EXPECTED_DATABASE = 'mannschaft_loyalty_cmp2610160950'
if (process.env.E2E_DB_NAME !== EXPECTED_DATABASE)
  throw new Error('This seed requires the dedicated loyalty database')
function required(name) {
  const value = process.env[name]
  if (!value) throw new Error(`Missing environment variable: ${name}`)
  return value
}
const require = createRequire(
  process.env.LOYALTY_SEED_DEPENDENCY_PACKAGE ??
    new URL('../../backend/scripts/package.json', import.meta.url),
)
const mysql = require('mysql2/promise')
const bcrypt = require('bcryptjs')
const encryptionKey = Buffer.from(required('MANNSCHAFT_ENCRYPTION_KEY'), 'base64')
if (encryptionKey.length !== 32) throw new Error('Encryption key must contain 32 bytes')
function encrypt(plain) {
  const iv = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', encryptionKey, iv)
  return Buffer.concat([
    iv,
    cipher.update(plain, 'utf8'),
    cipher.final(),
    cipher.getAuthTag(),
  ]).toString('base64')
}
const apiBase = required('API_BASE_URL')
if (!/^http:\/\/localhost:8081$/.test(apiBase))
  throw new Error('Seed API must be the dedicated localhost:8081 server')
const runId = randomBytes(5).toString('hex')
const roles = [
  'admin',
  'individual',
  'supporter',
  'member',
  'delegatedDeputy',
  'deputy',
  'otherTeamAdmin',
  'organizationAdmin',
  'systemAdmin',
  'resident1',
  'resident2',
  'resident3',
]
const actors = {}
const database = await mysql.createConnection({
  host: process.env.E2E_DB_HOST ?? '127.0.0.1',
  port: Number(process.env.E2E_DB_PORT ?? 3306),
  user: required('E2E_DB_USER'),
  password: required('E2E_DB_PASSWORD'),
  database: EXPECTED_DATABASE,
  charset: 'utf8mb4',
})
async function insert(sql, parameters) {
  return (await database.execute(sql, parameters))[0].insertId
}
async function login(actor) {
  const response = await fetch(`${apiBase}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: actor.email, password: actor.password }),
  })
  if (!response.ok) throw new Error(`Dedicated actor login failed (${response.status()})`)
  return response.headers
    .getSetCookie()
    .map((value) => value.split(';')[0])
    .join('; ')
}
async function post(cookie, path, body, method = 'POST') {
  const response = await fetch(`${apiBase}/api/v1${path}`, {
    method,
    headers: { Cookie: cookie, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw new Error(`Fixture preparation failed at ${path} (${response.status()})`)
  return (await response.json()).data
}
async function assign(userId, role, scopeType, scopeId, adminId) {
  if (!['MEMBER', 'SUPPORTER'].includes(role)) {
    const [catalog] = await database.execute('SELECT id FROM roles WHERE name=?', [role])
    if (catalog.length !== 1) throw new Error('Role catalog mismatch')
    await insert(
      'INSERT INTO user_roles (user_id,role_id,team_id,organization_id,granted_by,created_at,updated_at) VALUES (?,?,?,?,?,NOW(),NOW())',
      [
        userId,
        catalog[0].id,
        scopeType === 'TEAM' ? scopeId : null,
        scopeType === 'ORGANIZATION' ? scopeId : null,
        adminId,
      ],
    )
  }
  if (scopeType)
    await insert(
      'INSERT INTO memberships (user_id,scope_type,scope_id,role_kind,joined_at,invited_by,created_at,updated_at) VALUES (?,?,?,?,NOW(),?,NOW(),NOW())',
      [userId, scopeType, scopeId, role === 'SUPPORTER' ? 'SUPPORTER' : 'MEMBER', adminId],
    )
}
try {
  const [[selectedDatabase]] = await database.query('SELECT DATABASE() AS name')
  if (selectedDatabase.name !== EXPECTED_DATABASE) throw new Error('Database guard failed')
  await database.beginTransaction()
  for (const role of roles) {
    const password = `Loyalty!${randomBytes(20).toString('base64url')}`
    const email = `loyalty-${runId}-${role.toLowerCase()}@test.mannschaft.local`
    const name = `Loyalty ${role}`
    const id = await insert(
      'INSERT INTO users (email,password_hash,last_name,first_name,display_name,is_searchable,encryption_key_version,locale,timezone,status,reporting_restricted,created_at,updated_at) VALUES (?,?,?,?,?,1,1,?,?,?,0,NOW(),NOW())',
      [
        email,
        await bcrypt.hash(password, 12),
        encrypt('Loyalty'),
        encrypt(role),
        name,
        'ja',
        'Asia/Tokyo',
        'ACTIVE',
      ],
    )
    actors[role] = { id: Number(id), email, password }
  }
  await database.commit()
  const adminCookie = await login(actors.admin)
  const team = await post(adminCookie, '/teams', {
    name: `Loyalty ${runId}`,
    slug: `loyalty-${runId}`,
    template: 'OTHER',
    visibility: 'MEMBERS_AND_ABOVE',
  })
  const teamId = team.numericId
  if (!Number.isSafeInteger(teamId)) throw new Error('Team response must contain numericId')
  const otherCookie = await login(actors.otherTeamAdmin)
  await post(otherCookie, '/teams', {
    name: `Other ${runId}`,
    slug: `loyalty-other-${runId}`,
    template: 'OTHER',
    visibility: 'MEMBERS_AND_ABOVE',
  })
  const organizationCookie = await login(actors.organizationAdmin)
  await post(organizationCookie, '/organizations', {
    name: `Org ${runId}`,
    slug: `loyalty-org-${runId}`,
    orgType: 'OTHER',
    visibility: 'PRIVATE',
    supporterEnabled: false,
  })
  await database.beginTransaction()
  await assign(actors.member.id, 'MEMBER', 'TEAM', teamId, actors.admin.id)
  await assign(actors.supporter.id, 'SUPPORTER', 'TEAM', teamId, actors.admin.id)
  await assign(actors.deputy.id, 'DEPUTY_ADMIN', 'TEAM', teamId, actors.admin.id)
  await assign(actors.delegatedDeputy.id, 'DEPUTY_ADMIN', 'TEAM', teamId, actors.admin.id)
  await assign(actors.systemAdmin.id, 'SYSTEM_ADMIN', null, null, actors.admin.id)
  const [permission] = await database.execute(
    "SELECT id FROM permissions WHERE name='TEAM_LOYALTY_OPERATE'",
  )
  if (permission.length !== 1) throw new Error('TEAM_LOYALTY_OPERATE catalog is missing')
  const groupId = await insert(
    'INSERT INTO permission_groups (team_id,organization_id,target_role,name,created_by,created_at,updated_at) VALUES (?,NULL,?,?,?,NOW(),NOW())',
    [teamId, 'DEPUTY_ADMIN', `Loyalty ${runId}`, actors.admin.id],
  )
  await insert(
    'INSERT INTO permission_group_permissions (group_id,permission_id,created_at) VALUES (?,?,NOW())',
    [groupId, permission[0].id],
  )
  await insert(
    'INSERT INTO user_permission_groups (user_id,group_id,assigned_by,created_at) VALUES (?,?,?,NOW())',
    [actors.delegatedDeputy.id, groupId, actors.admin.id],
  )
  await database.commit()
  await post(
    adminCookie,
    `/teams/${teamId}/loyalty/settings`,
    {
      expectedVersion: null,
      isEnabled: true,
      targetAudience: 'ALL_AUTHENTICATED',
      selfAwardMode: 'STAFF_APPROVAL',
    },
    'PUT',
  )
  await post(adminCookie, `/teams/${teamId}/loyalty/rules`, {
    name: 'E2E来店',
    points: 25,
    frequency: 'ONCE_PER_JST_DAY',
    isSelfAwardEnabled: true,
  })
  await post(adminCookie, `/teams/${teamId}/loyalty/rewards`, {
    name: 'E2E特典',
    description: null,
    requiredPoints: 20,
  })
  const output = resolve(
    dirname(fileURLToPath(import.meta.url)),
    '../tests/e2e/.auth/loyalty-fixture.json',
  )
  await mkdir(dirname(output), { recursive: true })
  await writeFile(
    output,
    JSON.stringify({ runId, actors, team: { slug: team.slug, numericId: teamId } }),
    { mode: 0o600 },
  )
  console.log(
    'Dedicated loyalty fixture prepared; credentials were written only to the ignored fixture file.',
  )
} finally {
  await database.end()
}
