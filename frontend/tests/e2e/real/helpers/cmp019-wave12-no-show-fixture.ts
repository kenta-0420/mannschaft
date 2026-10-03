import { expect, type APIRequestContext } from '@playwright/test'

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`

export type NoShowScope = {
  type: 'TEAM' | 'ORGANIZATION'
  slug: string
  numericId: number
}

export type NoShowFixture = {
  scopeType: NoShowScope['type']
  scopeId: number
  scopeSlug: string
  listingIds: number[]
  participantIds: number[]
  targetUserId: number
  listingId: number
  participantId: number
  title: string
}

export type NoShowCredentials = { email: string; password: string }

type ApiEnvelope<T> = { data: T }

export function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

export async function loginForNoShow(
  request: APIRequestContext,
  credentials: NoShowCredentials,
): Promise<{ token: string; userId: number }> {
  const response = await request.post(`${API}/auth/login`, { data: credentials })
  expect(response.status(), `${credentials.email} のログイン`).toBe(200)
  const token = ((await response.json()) as ApiEnvelope<{ accessToken: string }>).data.accessToken
  const me = await request.get(`${API}/users/me`, { headers: authHeaders(token) })
  expect(me.status(), `${credentials.email} の /users/me`).toBe(200)
  const userId = ((await me.json()) as ApiEnvelope<{ id: number }>).data.id
  return { token, userId }
}

async function createListing(
  request: APIRequestContext,
  scope: NoShowScope,
  adminToken: string,
  categoryId: number,
  title: string,
): Promise<{ listingId: number; scopeId: number }> {
  const now = Date.now()
  const asLocalDateTime = (deltaHours: number) => {
    const date = new Date(now + deltaHours * 60 * 60 * 1000)
    const parts = new Intl.DateTimeFormat('sv-SE', {
      timeZone: 'Asia/Tokyo',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).formatToParts(date)
    const get = (type: string) => parts.find((part) => part.type === type)!.value
    return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}:${get('second')}`
  }
  const path = scope.type === 'TEAM' ? 'teams' : 'organizations'
  const create = await request.post(`${API}/${path}/${scope.numericId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId,
      title,
      description: `CMP-019 Wave12 fixture ${title}`,
      participationType: 'INDIVIDUAL',
      startAt: asLocalDateTime(48),
      endAt: asLocalDateTime(50),
      applicationDeadline: asLocalDateTime(24),
      autoCancelAt: asLocalDateTime(23),
      capacity: 10,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
      location: 'Wave12 E2E fixture',
    },
  })
  expect(create.status(), `募集作成: ${await create.text()}`).toBe(201)
  const listing = ((await create.json()) as ApiEnvelope<{ id: number; scopeId: number }>).data
  const targets = await request.put(
    `${API}/recruitment-listings/${listing.id}/distribution-targets`,
    {
      headers: authHeaders(adminToken),
      data: { targetTypes: ['PUBLIC_FEED'] },
    },
  )
  expect(targets.status(), `公開フィード配信対象: ${await targets.text()}`).toBe(200)
  const publish = await request.post(`${API}/recruitment-listings/${listing.id}/publish`, {
    headers: authHeaders(adminToken),
  })
  expect(publish.status(), `募集公開: ${await publish.text()}`).toBe(200)
  return { listingId: listing.id, scopeId: listing.scopeId }
}

async function addConfirmedParticipant(
  request: APIRequestContext,
  listingId: number,
  adminToken: string,
  applicantToken: string,
): Promise<number> {
  const apply = await request.post(`${API}/recruitment-listings/${listingId}/applications`, {
    headers: authHeaders(applicantToken),
    data: { participantType: 'USER' },
  })
  expect(apply.status(), `応募: ${await apply.text()}`).toBe(201)
  const participant = ((await apply.json()) as ApiEnvelope<{ id: number; status: string }>).data
  if (participant.status !== 'CONFIRMED') {
    const confirm = await request.post(
      `${API}/recruitment-listings/${listingId}/participants/${participant.id}/confirm`,
      { headers: authHeaders(adminToken) },
    )
    expect(confirm.status(), `応募承認: ${await confirm.text()}`).toBe(200)
    expect(((await confirm.json()) as ApiEnvelope<{ status: string }>).data.status).toBe(
      'CONFIRMED',
    )
  }
  return participant.id
}

/** Wave12 専用の募集と参加者を API で作り、返した ID だけを後続の cleanup 対象にする。 */
export async function createNoShowFixture(
  request: APIRequestContext,
  scope: NoShowScope,
  adminToken: string,
  targetToken: string,
  targetUserId: number,
  runTag: string,
): Promise<NoShowFixture> {
  const categoryResponse = await request.get(`${API}/recruitment-categories`, {
    headers: authHeaders(adminToken),
  })
  expect(categoryResponse.status(), '募集カテゴリ取得').toBe(200)
  const categories = ((await categoryResponse.json()) as ApiEnvelope<Array<{ id: number }>>).data
  expect(categories.length, '有効な募集カテゴリ').toBeGreaterThan(0)
  const category = categories[0]
  if (!category) throw new Error('有効な募集カテゴリがありません')

  const first = await createListing(
    request,
    scope,
    adminToken,
    category.id,
    `${runTag}_${scope.type}_A`,
  )
  const second = await createListing(
    request,
    scope,
    adminToken,
    category.id,
    `${runTag}_${scope.type}_B`,
  )
  expect(second.scopeId, '2件の試験募集は同一 scope に属する').toBe(first.scopeId)
  const participantId = await addConfirmedParticipant(
    request,
    first.listingId,
    adminToken,
    targetToken,
  )
  const otherParticipantId = await addConfirmedParticipant(
    request,
    second.listingId,
    adminToken,
    targetToken,
  )

  expect(first.scopeId, '作成した募集の scope ID は正数').toBeGreaterThan(0)
  return {
    scopeType: scope.type,
    scopeId: first.scopeId,
    scopeSlug: scope.slug,
    listingIds: [first.listingId, second.listingId],
    participantIds: [participantId, otherParticipantId],
    targetUserId,
    listingId: first.listingId,
    participantId,
    title: `${runTag}_${scope.type}_A`,
  }
}
