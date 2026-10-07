import { expect, type Page, type APIResponse } from '@playwright/test'
import mysql, { type RowDataPacket, type Connection } from '../../../../backend/scripts/node_modules/mysql2/promise'

export const API = 'http://localhost:8081/api/v1'
export const PASSWORD = 'TestPass2026!'
export const ADMIN = 'e2e-admin@test.mannschaft.local'
export const READER = 'e2e-dummy-2@test.mannschaft.local'
export const SCHEDULE_EDITOR = 'e2e-dummy-3@test.mannschaft.local'
export const OUTSIDER = 'e2e-outsider@test.mannschaft.local'
export const OTHER_TENANT = 'e2e-dummy-7@test.mannschaft.local'

export interface Scope { type: 'TEAM' | 'ORGANIZATION'; id: number; slug: string; path: string }
export interface Activity {
  id: number; title: string; version: number; status: string; description: string | null
  activityDate: string; activityEndDate: string | null
  activityTimeStart: string | null; activityTimeEnd: string | null
  fieldValues: string | null; attachments: string | null
  participants: Array<{ userId: number }>
}

export async function data<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `${response.url()} ${response.status()} ${await response.text()}`).toBeTruthy()
  return (await response.json() as { data: T }).data
}

async function database(): Promise<Connection> {
  const values = ['E2E_DB_PORT', 'E2E_DB_NAME', 'E2E_DB_USER', 'E2E_DB_PASSWORD'] as const
  const expected = ['13310', 'cmp2610071510', 'cmp_test', 'cmp-test-only']
  if (values.some((name, index) => process.env[name] !== expected[index])) {
    throw new Error('fixture は専用 DB 13310/cmp2610071510 の明示指定を必須とします')
  }
  return mysql.createConnection({ host: '127.0.0.1', port: 13310,
    database: 'cmp2610071510', user: 'cmp_test', password: process.env.E2E_DB_PASSWORD })
}

// SQL は seed の scope 解決とテスト用会員の前提作成のみ。業務操作は実 UI/API を通す。
export async function resolveScope(type: Scope['type']): Promise<Scope> {
  const db = await database()
  try {
    const table = type === 'TEAM' ? 'teams' : 'organizations'
    const name = type === 'TEAM' ? 'FC東京U-18（テスト）' : '日本サッカー協会（テスト）'
    const [rows] = await db.execute<RowDataPacket[]>(`SELECT id, slug FROM ${table} WHERE name = ?`, [name])
    expect(rows, 'seed scope は一意').toHaveLength(1)
    const id = Number(rows[0]!.id)
    const slug = String(rows[0]!.slug)
    return { type, id, slug, path: `/${table}/${slug}` }
  } finally { await db.end() }
}

export class ActivitySyncFixture {
  readonly activityIds: number[] = []
  readonly scheduleIds: number[] = []
  readonly templateIds: number[] = []
  private editorGroup: { id: number; userId: number } | null = null
  constructor(readonly page: Page, readonly scope: Scope) {}
  get query(): string { return `scope_type=${this.scope.type}&scope_id=${this.scope.id}` }
  get schedules(): string { return `${API}/${this.scope.type === 'TEAM' ? 'teams' : 'organizations'}/${this.scope.id}/schedules` }

  async schedule(title: string): Promise<number> {
    const schedule = await data<{ id: number }>(await this.page.request.post(this.schedules, { data: {
      title, startAt: '2026-10-15T23:00:00+09:00', endAt: '2026-10-16T01:00:00+09:00',
      allDay: false, eventType: 'EVENT', visibility: 'MEMBERS_ONLY', attendanceRequired: false,
      description: '予定本文は活動本文に同期しない', location: '専用会場',
    } }))
    this.scheduleIds.push(schedule.id)
    return schedule.id
  }

  async linkedDraft(scheduleId: number): Promise<Activity> {
    const activity = await data<Activity>(await this.page.request.post(`${API}/activities/draft-from-schedule?${this.query}`, { data: { scheduleId } }))
    this.activityIds.push(activity.id)
    return activity
  }

  async published(title: string, scheduleId?: number): Promise<Activity> {
    const template = await data<{ id: number }>(await this.page.request.post(`${API}/activity-templates?${this.query}`, { data: {
      name: title, fields: [
        { fieldKey: 'numberZero', fieldLabel: 'ゼロ数値', fieldType: 'NUMBER', isRequired: false },
        { fieldKey: 'checkboxFalse', fieldLabel: '未チェック', fieldType: 'CHECKBOX', isRequired: false },
      ],
    } }))
    this.templateIds.push(template.id)
    const activity = await data<Activity>(await this.page.request.post(`${API}/activities?${this.query}`, { data: {
      title, templateId: template.id, activityDate: '2026-10-15', description: '**保持する活動本文**',
      fieldValues: { numberZero: 0, checkboxFalse: false, historicalNull: null, unknownOldKey: '過去値' },
      visibility: 'MEMBERS_ONLY', participantUserIds: [], fileIds: [], postToTimeline: false, scheduleId,
    } }))
    this.activityIds.push(activity.id)
    return activity
  }

  async detail(id: number): Promise<Activity> {
    return data<Activity>(await this.page.request.get(`${API}/activities/${id}`))
  }

  async scheduleOnlyEditor(): Promise<void> {
    const db = await database()
    let userId: number
    let permissionId: number
    try {
      const [users] = await db.execute<RowDataPacket[]>('SELECT id FROM users WHERE email = ?', [SCHEDULE_EDITOR])
      const [permissions] = await db.execute<RowDataPacket[]>("SELECT id FROM permissions WHERE name = 'MANAGE_SCHEDULES'")
      expect(users).toHaveLength(1)
      expect(permissions).toHaveLength(1)
      userId = Number(users[0]!.id)
      permissionId = Number(permissions[0]!.id)
      const [groups] = await db.execute<RowDataPacket[]>('SELECT id FROM user_permission_groups WHERE user_id = ?', [userId])
      expect(groups, 'seed ユーザーの既存権限割当を上書きしない').toHaveLength(0)
    } finally { await db.end() }
    const query = `scopeType=${this.scope.type}&scopeId=${this.scope.id}`
    const group = await data<{ id: number }>(await this.page.request.post(`${API}/admin/permission-groups?${query}`, {
      data: { name: `活動同期実機-${Date.now()}`, targetRole: 'MEMBER', permissionIds: [permissionId] },
    }))
    this.editorGroup = { id: group.id, userId }
    await data(await this.page.request.patch(`${API}/admin/permission-groups/${group.id}/assign/${userId}?${query}`))
  }

  async cleanup(): Promise<void> {
    // 所有 ID だけを後始末する。seed fixture 一括削除・TRUNCATE は行わない。
    for (const id of this.activityIds) await data(await this.page.request.delete(`${API}/activities/${id}`))
    for (const id of this.scheduleIds) await data(await this.page.request.delete(`${this.schedules}/${id}?delete_scope=THIS_ONLY`))
    for (const id of this.templateIds) await data(await this.page.request.delete(`${API}/activity-templates/${id}`))
    if (this.editorGroup) {
      const query = `scopeType=${this.scope.type}&scopeId=${this.scope.id}`
      await data(await this.page.request.patch(`${API}/admin/permission-groups/${this.editorGroup.id}/unassign/${this.editorGroup.userId}?${query}`))
      await data(await this.page.request.delete(`${API}/admin/permission-groups/${this.editorGroup.id}?${query}`))
    }
  }
}
