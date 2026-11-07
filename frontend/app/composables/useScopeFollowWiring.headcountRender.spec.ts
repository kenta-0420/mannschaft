import { defineComponent, h, ref } from 'vue'
import type { Ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import type { TeamResponse } from '~/types/team'
import type { OrgDetail } from '~/composables/useOrgDetail'
import type { FollowStatusApi } from '~/composables/useFollowSelfStatus'
import type { JoinRequestUiStatus } from '~/composables/useJoinRequestApi'
import TeamPageHeader from '~/components/team/TeamPageHeader.vue'
import OrgPageHeader from '~/components/organization/OrgPageHeader.vue'

/**
 * 応援・解除 → 人数取り直し → ヘッダの「サポーター ◯人」の実描画まで通す結合の試練
 * （CMP-261004-1942）。
 *
 * - モックは API 境界（useApi と follow/unfollow/getStatus）とエラー通知だけ。
 *   useScopeFollowWiring / useFollowSelfStatus / useRoleAccess / useOrgDetail は実物を通す。
 * - BE 側の人数は follow/unfollow の成功で増減させ、詳細 GET がその時点の人数を返す。
 *   したがってヘッダの表示が変わるのは「変更成功後に詳細を取り直した」場合だけである。
 * - チームの詳細取得はページ内関数（pages/teams/[slug].vue の fetchTeam）のため、
 *   ここでは同じ API（useTeamApi().getTeam）で team を置き換える最小の取得関数を渡す。
 *   ページが fetchTeam を refreshDetail として渡すことは headcount.spec.ts のソース固定で担保。
 * - 組織は実物の useOrgDetail().fetchOrg を refreshDetail として渡す。
 *
 * ヘッダのサポーター欄は `data-testid="scope-header-supporter-count"` で特定する（新設）。
 */

const handleApiErrorMock = vi.fn()
const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()
const apiMock = vi.fn()

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: notificationSuccessMock, error: notificationErrorMock }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

const { useScopeFollowWiring } = await import('~/composables/useScopeFollowWiring')
const { useRoleAccess } = await import('~/composables/useRoleAccess')
const { useOrgDetail } = await import('~/composables/useOrgDetail')
const { useTeamApi } = await import('~/composables/useTeamApi')

const MenuStub = { props: ['model'], template: '<div />' }
const stubs = {
  ProfileHeader: { template: '<div><slot /></div>' },
  FavoriteToggleButton: true,
  RoleBadge: true,
  Tag: true,
  Menu: MenuStub,
  BroadcastWizard: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function deferred<T = unknown>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function forbiddenError() {
  return Object.assign(new Error('Forbidden'), { statusCode: 403, status: 403, response: { status: 403 } })
}

/** BE 側の状態（スコープごとのサポーター人数）。follow/unfollow の成功で増減する。 */
const backend = { supporters: new Map<string, number>() }

function makeFollowApi(overrides: Partial<FollowStatusApi> = {}): FollowStatusApi {
  return {
    follow: vi.fn(async (scopeId: string) => {
      backend.supporters.set(scopeId, (backend.supporters.get(scopeId) ?? 0) + 1)
      return {}
    }),
    unfollow: vi.fn(async (scopeId: string) => {
      backend.supporters.set(scopeId, Math.max(0, (backend.supporters.get(scopeId) ?? 0) - 1))
      return {}
    }),
    getStatus: vi.fn(async (scopeId: string) => ({
      data: { status: (backend.supporters.get(scopeId) ?? 0) > 0 ? 'APPROVED' as const : 'NONE' as const },
    })),
    ...overrides,
  }
}

function teamBody(slug: string): TeamResponse {
  return {
    id: 1,
    numericId: 1,
    slug,
    visibility: { visibility: 'PUBLIC', supporterEnabled: true },
    metadata: { memberCount: 3 },
    social: { supporterCount: backend.supporters.get(slug) ?? 0 },
    location: { template: 'default' },
  } as unknown as TeamResponse
}

function orgBody(slug: string): OrgDetail {
  return {
    id: slug,
    numericId: 1,
    basicInfo: { name: slug },
    visibility: { visibility: 'PUBLIC', supporterEnabled: true },
    metadata: { memberCount: 3 },
    social: { supporterCount: backend.supporters.get(slug) ?? 0 },
  } as unknown as OrgDetail
}

/** 既定の API 境界: 権限=未所属、詳細 GET=その時点の人数。 */
function defaultApi(url: string): Promise<unknown> {
  if (url.endsWith('/me/permissions')) return Promise.resolve({ data: { roleName: null, permissions: [] } })
  const team = url.match(/^\/api\/v1\/teams\/([^/]+)$/)
  if (team?.[1]) return Promise.resolve({ data: teamBody(team[1]) })
  const org = url.match(/^\/api\/v1\/organizations\/([^/]+)$/)
  if (org?.[1]) return Promise.resolve({ data: orgBody(org[1]) })
  return Promise.resolve({ data: [] })
}

function supporterText(wrapper: VueWrapper): string {
  const el = wrapper.find('[data-testid="scope-header-supporter-count"]')
  expect(el.exists()).toBe(true)
  return el.text()
}

function supporterNumber(wrapper: VueWrapper): string | undefined {
  return supporterText(wrapper).match(/\d+/)?.[0]
}

interface WiringHandle {
  slug: Ref<string>
  wiring: ReturnType<typeof useScopeFollowWiring>
  refresh: () => Promise<unknown>
}

function headerCommonProps(wiring: ReturnType<typeof useScopeFollowWiring>, roleName: string | null) {
  // ヘッダ props のリテラル union のまま渡す（string に広げると props の型に合わない）
  const joinRequestStatus: JoinRequestUiStatus = 'UNKNOWN'
  return {
    roleName,
    isAdmin: false,
    isAdminOrDeputy: false,
    followStatus: wiring.followStatus.value,
    followLoading: wiring.followLoading.value,
    followPermissionSyncError: wiring.followPermissionSyncError.value,
    joinRequestStatus,
    joinRequestLoading: false,
  }
}

async function mountTeam(followApi: FollowStatusApi) {
  let handle: WiringHandle | null = null
  const Harness = defineComponent({
    setup() {
      const slug = ref('team-a')
      const team = ref<TeamResponse | null>(null)
      const teamApi = useTeamApi()
      const { roleName, loadPermissions } = useRoleAccess('team', slug)
      async function refreshTeam() {
        const result = await teamApi.getTeam(slug.value)
        team.value = result.data
      }
      const wiring = useScopeFollowWiring({
        scopeSlug: slug,
        api: followApi,
        roleAccess: { roleName, loadPermissions },
        refreshDetail: refreshTeam,
      })
      handle = { slug, wiring, refresh: refreshTeam }
      return () => team.value
        ? h(TeamPageHeader, {
            ...headerCommonProps(wiring, roleName.value),
            team: team.value,
            displayName: 'チームA',
            templateLabel: {},
          })
        : h('div', { 'data-testid': 'detail-closed' })
    },
  })
  const wrapper = await mountSuspended(Harness, { global: { stubs } })
  if (!handle) throw new Error('harness not initialised')
  const h0 = handle as WiringHandle
  await h0.refresh()
  await wrapper.vm.$nextTick()
  return { wrapper, ...h0 }
}

async function mountOrg(followApi: FollowStatusApi) {
  let handle: (WiringHandle & { detail: ReturnType<typeof useOrgDetail> }) | null = null
  const Harness = defineComponent({
    setup() {
      const slug = ref('org-a')
      const detail = useOrgDetail(slug)
      const { roleName, loadPermissions } = useRoleAccess('organization', slug)
      const wiring = useScopeFollowWiring({
        scopeSlug: slug,
        api: followApi,
        roleAccess: { roleName, loadPermissions },
        refreshDetail: detail.fetchOrg,
      })
      handle = { slug, wiring, refresh: detail.fetchOrg, detail }
      return () => detail.org.value
        ? h(OrgPageHeader, {
            ...headerCommonProps(wiring, roleName.value),
            org: detail.org.value,
            orgId: slug.value,
            ancestors: [],
          })
        : h('div', { 'data-testid': 'detail-closed' })
    },
  })
  const wrapper = await mountSuspended(Harness, { global: { stubs } })
  if (!handle) throw new Error('harness not initialised')
  const h0 = handle as WiringHandle & { detail: ReturnType<typeof useOrgDetail> }
  await h0.refresh()
  await wrapper.vm.$nextTick()
  return { wrapper, ...h0 }
}

describe('応援・解除でヘッダのサポーター人数が実描画で更新される（CMP-261004-1942）', () => {
  beforeEach(() => {
    handleApiErrorMock.mockReset()
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
    apiMock.mockReset()
    apiMock.mockImplementation(defaultApi)
    backend.supporters.clear()
  })

  it('AC-1: チーム。応援でサポーター表示が 0→1、解除で 1→0 になる', async () => {
    const { wrapper, wiring } = await mountTeam(makeFollowApi())
    expect(supporterNumber(wrapper)).toBe('0')

    await wiring.applySupporter()
    await vi.waitFor(() => expect(supporterNumber(wrapper)).toBe('1'))

    await wiring.cancelSupporter()
    await vi.waitFor(() => expect(supporterNumber(wrapper)).toBe('0'))
  })

  it('AC-2: 組織。応援で org.social.supporterCount の表示が 0→1、解除で 1→0 になる（fetchOrg 経由）', async () => {
    const { wrapper, wiring } = await mountOrg(makeFollowApi())
    expect(supporterNumber(wrapper)).toBe('0')

    await wiring.applySupporter()
    await vi.waitFor(() => expect(supporterNumber(wrapper)).toBe('1'))
    expect(apiMock.mock.calls.filter(([url]) => url === '/api/v1/organizations/org-a')).toHaveLength(2)

    await wiring.cancelSupporter()
    await vi.waitFor(() => expect(supporterNumber(wrapper)).toBe('0'))
  })

  it('AC-3: 組織。follow API が失敗したら詳細を取り直さず表示も変えない', async () => {
    const { wrapper, wiring } = await mountOrg(makeFollowApi({ follow: vi.fn().mockRejectedValue(new Error('boom')) }))

    await wiring.applySupporter()
    await wrapper.vm.$nextTick()

    expect(apiMock.mock.calls.filter(([url]) => url === '/api/v1/organizations/org-a')).toHaveLength(1)
    expect(supporterNumber(wrapper)).toBe('0')
  })

  it('AC-5: 組織。A の取り直し中に B へ切り替わり B を表示した後、A の遅延応答で B の表示を上書きしない', async () => {
    const followApi = makeFollowApi()
    const { wrapper, slug, wiring, detail } = await mountOrg(followApi)
    const lateA = deferred<unknown>()
    let orgAGets = 0
    apiMock.mockImplementation((url: string) => {
      if (url === '/api/v1/organizations/org-a') {
        orgAGets++
        if (orgAGets === 1) return lateA.promise
      }
      return defaultApi(url)
    })
    backend.supporters.set('org-b', 5)

    const applying = wiring.applySupporter()
    // 応援成功後の取り直し（A 向け GET）が飛ぶまで待つ。
    await vi.waitFor(() => expect(orgAGets).toBe(1))
    slug.value = 'org-b'
    await detail.fetchOrg()
    await vi.waitFor(() => expect(supporterNumber(wrapper)).toBe('5'))

    lateA.resolve({ data: orgBody('org-a') })
    await applying
    await new Promise(resolve => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(detail.org.value?.id).toBe('org-b')
    expect(supporterNumber(wrapper)).toBe('5')
  })

  it('AC-11: 組織。解除成功後の取り直しが 403 なら保護された詳細表示を閉じ、読み込み中で詰まらない', async () => {
    backend.supporters.set('org-a', 1)
    const { wrapper, wiring, detail } = await mountOrg(makeFollowApi())
    expect(supporterNumber(wrapper)).toBe('1')
    apiMock.mockImplementation((url: string) =>
      url === '/api/v1/organizations/org-a' ? Promise.reject(forbiddenError()) : defaultApi(url))

    await wiring.cancelSupporter()
    await vi.waitFor(() => expect(wrapper.find('[data-testid="detail-closed"]').exists()).toBe(true))

    expect(detail.org.value).toBeNull()
    expect(detail.loading.value).toBe(false)
    expect(wiring.followLoading.value).toBe(false)
  })
})
