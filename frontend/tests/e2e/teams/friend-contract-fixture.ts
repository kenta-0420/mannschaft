import { readFileSync } from 'node:fs'
import { isDeepStrictEqual } from 'node:util'
import type { Page } from '@playwright/test'
import type { TeamFriendListResponse, FollowTeamResponse } from '../../../app/types/friends'
import type { FriendFeedResponse } from '../../../app/types/friendFeed'
import type { TeamFriendFolderView } from '../../../app/types/friendFolders'
import type { ForwardResponse, FriendForwardExportListResponse } from '../../../app/types/friendForward'

const timestamp = '2026-10-21T03:00:00Z'
const pagination = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }
const friend = { teamFriendId: 1, friendTeamId: 2, friendTeamName: 'Rival FC', public: true, establishedAt: timestamp }
const folder = { id: 1, name: 'Training partners', description: null, color: '#6B7280', memberCount: 0, createdAt: timestamp, updatedAt: timestamp } satisfies TeamFriendFolderView
const post = { postId: 10, sourceTeam: { id: 2, name: 'Rival FC' }, content: 'Invitation to a training match', receivedAt: timestamp, forwardStatus: { isForwarded: false, forwardId: null, forwardedAt: null } }
const team = {
  id: '1', numericId: 1, slug: '1', name: 'Baseline Football Club', role: 'ADMIN', template: 'SPORTS', memberCount: 5, teamFriendCount: 1, supporterEnabled: false, version: 1,
  basicInfo: { name: 'Baseline Football Club', nickname1: null, nickname2: null, nameKana: null, description: 'Baseline' },
  location: { template: 'SPORTS', prefectureCode: null, cityCode: null },
  visibility: { visibility: 'PUBLIC', supporterEnabled: false },
  metadata: { memberCount: 5, iconUrl: null, bannerUrl: null },
  social: { supporterCount: 0, teamFriendCount: 1 },
  timestamps: { createdAt: timestamp, updatedAt: timestamp, archivedAt: null },
}
const user = { id: 1, email: 'mock@example.invalid', fullName: 'Contract User', systemRole: 'SYSTEM_ADMIN', timezone: 'Asia/Tokyo', locale: 'ja' }
const permissions = { roleName: 'ADMIN', scopeAdmin: true, permissions: ['MANAGE_FRIEND_TEAMS', 'MANAGE_SETTINGS', 'MANAGE_ADVERTISING'] }

/** モックE2Eの通信契約。フレンド領域の未知URL/メソッド/クエリは500と記録で必ず落とす。 */
export async function installFriendContractFixture(page: Page, locale = 'ja') {
  const state = {
    friends: [friend] as TeamFriendListResponse['data'],
    folders: [folder] as TeamFriendFolderView[],
    feed: [structuredClone(post)] as FriendFeedResponse['data'],
    conflict: false,
    calls: [] as Array<{ method: string; path: string; query: string; body: unknown }>,
    unexpected: [] as string[],
  }
  await page.context().addCookies([{ name: 'i18n_redirected', value: locale, domain: 'localhost', path: '/' }])
  await page.addInitScript(({ user }) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
    localStorage.setItem('currentScope', JSON.stringify({ type: 'team', id: '1', name: 'Baseline Football Club' }))
    localStorage.setItem('tokenExpiresAt', '4102444800000')
    localStorage.setItem('appearanceMode', 'LIGHT')
  }, { user: { ...user, locale } })
  const flags = [...new Set(readFileSync('app/constants/featureGates.ts', 'utf8').match(/FEATURE_[A-Z0-9_]+/g) ?? [])].map(flagKey => ({ flagKey, enabled: true }))
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const body: unknown = request.postDataJSON()
    const send = (value: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    const domain = /^\/api\/v1\/teams\/1\/(friends|friend-folders|friend-feed|friend-forward-exports)(\/|$)/.test(path)
    if (domain) {
      state.calls.push({ method, path, query: url.search, body })
      const listQuery = url.searchParams.get('page') === '0' && url.searchParams.get('size') === '20' && [...url.searchParams].length === 2
      if (path === '/api/v1/teams/1/friends' && method === 'GET' && listQuery) return send({ data: state.friends, pagination: { ...pagination, totalElements: state.friends.length, totalPages: state.friends.length ? 1 : 0 } } satisfies TeamFriendListResponse)
      if (path === '/api/v1/teams/1/friends/follow' && method === 'POST' && isDeepStrictEqual(body, { targetTeamSlug: 'rival-fc' })) {
        state.friends = [friend]
        return send({ data: { followId: 3, followerTeamId: 1, followedTeamId: 2, mutual: true, teamFriendId: 1, establishedAt: timestamp, isPublic: true, createdAt: timestamp } satisfies FollowTeamResponse }, 201)
      }
      if (path === '/api/v1/teams/1/friends/follow/2' && method === 'DELETE' && isDeepStrictEqual(body, { pastForwardHandling: 'KEEP' })) { state.friends = []; return route.fulfill({ status: 204 }) }
      if (path === '/api/v1/teams/1/friend-folders' && method === 'GET' && !url.search) return send({ data: state.folders } satisfies { data: TeamFriendFolderView[] })
      if (path === '/api/v1/teams/1/friend-folders' && method === 'POST' && isDeepStrictEqual(body, { name: 'New folder', color: '#6B7280' })) {
        const created = { ...folder, id: 3, name: 'New folder' }; state.folders.push(created); return send({ data: created }, 201)
      }
      if (path === '/api/v1/teams/1/friend-folders/1' && method === 'DELETE' && body === null) { state.folders = state.folders.filter(item => item.id !== 1); return route.fulfill({ status: 204 }) }
      if (path === '/api/v1/teams/1/friend-feed' && method === 'GET' && !url.search) return send({ data: state.feed, meta: { nextCursor: null, limit: 20, hasNext: false } } satisfies FriendFeedResponse)
      if (path === '/api/v1/teams/1/friend-feed/10/forward' && method === 'POST' && isDeepStrictEqual(body, { target: 'MEMBER', comment: 'Approved' })) {
        state.feed = [{ ...post, forwardStatus: { isForwarded: true, forwardId: 7, forwardedAt: timestamp } }]
        if (state.conflict) return send({ error: { code: 'SOCIAL_121', message: 'この投稿は既に転送済みです' } }, 409)
        return send({ data: { forwardId: 7, sourcePostId: 10, forwardedPostId: 20, target: 'MEMBER', forwardedAt: timestamp } satisfies ForwardResponse }, 201)
      }
      if (path === '/api/v1/teams/1/friend-forward-exports' && method === 'GET' && listQuery) return send({ data: [{ forwardId: 7, sourcePostId: 20, forwardingTeamName: 'Rival FC', target: 'MEMBER', forwardedAt: timestamp, isRevoked: false }], pagination } satisfies FriendForwardExportListResponse)
      state.unexpected.push(method + ' ' + path + url.search + ' ' + JSON.stringify(body))
      return send({ error: { code: 'FIXTURE_UNEXPECTED', message: 'Unexpected friend contract' } }, 500)
    }
    if (path.endsWith('/feature-flags')) return send({ data: flags })
    if (path.endsWith('/me/permissions')) return send({ data: permissions })
    if (path === '/api/v1/teams/1') return send({ data: team })
    if (path === '/api/v1/organizations/test-org') return send({ data: { id: 'test-org', numericId: 12, slug: 'test-org', name: 'Baseline Organization', role: 'ADMIN', visibility: 'PUBLIC', memberCount: 5, template: 'SPORTS', version: 1 } })
    if (/\/(?:my|me)\/organizations$|\/organizations\/my$/.test(path)) return send({ data: [{ id: 12, slug: 'test-org', name: 'Baseline Organization', role: 'ADMIN' }] })
    if (path.endsWith('/performance')) return send({ data: { campaignId: 7, campaignName: 'Baseline Campaign', status: 'ACTIVE', pricingModel: 'CPM', summary: { totalImpressions: 0, totalClicks: 0, avgCtr: 0, totalCost: 0, avgCpm: null, avgCpc: null, conversions: null, conversionRate: null, costPerConversion: null }, benchmark: null, points: [] } })
    if (path.endsWith('/creatives/comparison')) return send({ data: { campaignId: 7, creatives: [], winner: null } })
    if (path.endsWith('/breakdown')) return send({ data: { campaignId: 7, breakdownBy: 'PREFECTURE', items: [] } })
    if (/\/(?:my|me)\/teams$|\/teams\/my$/.test(path)) return send({ data: [{ ...team, id: 1 }] })
    if (path.endsWith('/me') || path.endsWith('/auth/me')) return send({ data: user })
    if (path.includes('/appearance')) return send({ data: { mode: 'LIGHT', theme: 'LIGHT', locale, timezone: 'Asia/Tokyo' } })
    if (path.includes('/modules/catalog')) return send({ data: { modules: [], scopeType: 'TEAM', slug: '1' } })
    return send({ data: [], pagination: { ...pagination, totalElements: 0, totalPages: 0 }, meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
  })
  return state
}
