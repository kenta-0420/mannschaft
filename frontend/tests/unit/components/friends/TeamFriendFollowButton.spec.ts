/**
 * CMP-261012-0315 フレンドチームのフォロー先指定 — TeamFriendFollowButton の試練（red）。
 *
 * 実装側が満たすべき契約:
 * - props: `teamId: string`（自チーム slug）/ `targetTeamSlug: string`（フォロー先 slug）/
 *   `comment?: string` / `disabled?: boolean`。数値 `targetTeamId` prop は廃止する。
 * - 送信は `useFriendTeamsApi().follow(teamId, { targetTeamSlug, comment })` →
 *   `POST /api/v1/teams/{teamId}/friends/follow`、body は `{ targetTeamSlug, comment }`。
 *   body に `targetTeamId` を含めない（BE は両方指定を 400 にする）。
 * - 型 `FollowTeamRequest`（app/types/friends.ts）は `targetTeamId?: number` と `targetTeamSlug?: string`
 *   のどちらか一方を持つ形にする。
 * - 失敗時は `useErrorHandler().handleApiError(error)` を呼び、`followed` を emit しない（AC-11）。
 * - 呼び出し元から渡された `data-testid` は描画される <button> 要素に載る（属性フォールスルー）。
 */
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamFriendFollowButton from '~/components/friends/TeamFriendFollowButton.vue'

const { mockApi, notifySuccess, notifyError, handleApiError } = vi.hoisted(() => ({
  mockApi: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  handleApiError: vi.fn(),
}))

mockNuxtImport('useApi', () => () => mockApi)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError, warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))

const FOLLOW_RESPONSE = {
  followId: 100,
  followerTeamId: 10,
  followedTeamId: 20,
  mutual: false,
  teamFriendId: null,
  establishedAt: null,
  isPublic: null,
  createdAt: '2026-10-12T03:15:00',
  retryAfterSeconds: null,
}

async function mountButton(props: Record<string, unknown> = {}) {
  return mountSuspended(TeamFriendFollowButton, {
    props: { teamId: 'my-team', targetTeamSlug: 'rival-fc', ...props },
    attrs: { 'data-testid': 'friend-follow-submit' },
  })
}

describe('TeamFriendFollowButton（CMP-261012-0315）', () => {
  beforeEach(() => {
    mockApi.mockReset()
    notifySuccess.mockReset()
    notifyError.mockReset()
    handleApiError.mockReset()
  })

  it('AC-8: クリックすると body に targetTeamSlug を入れて自チームの follow API を呼ぶ（targetTeamId は送らない）', async () => {
    mockApi.mockResolvedValueOnce({ data: FOLLOW_RESPONSE })
    const wrapper = await mountButton({ comment: 'よろしく' })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(mockApi).toHaveBeenCalledTimes(1)
    const [url, options] = mockApi.mock.calls[0] as [string, { method: string, body: Record<string, unknown> }]
    expect(url).toBe('/api/v1/teams/my-team/friends/follow')
    expect(options.method).toBe('POST')
    expect(options.body.targetTeamSlug).toBe('rival-fc')
    expect(options.body.comment).toBe('よろしく')
    expect(options.body).not.toHaveProperty('targetTeamId')
    expect(wrapper.emitted('followed')?.[0]?.[0]).toMatchObject({ followId: 100 })
  })

  it('AC-11: 送信に失敗したら handleApiError を呼び、followed を emit しない', async () => {
    mockApi.mockRejectedValueOnce(Object.assign(new Error('Conflict'), { statusCode: 409 }))
    const wrapper = await mountButton()

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('followed')).toBeUndefined()
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  })

  it('呼び出し元の data-testid は <button> 要素に載る', async () => {
    const wrapper = await mountButton()
    expect(wrapper.find('button[data-testid="friend-follow-submit"]').exists()).toBe(true)
  })

  it('再入ガード: API 未解決のまま同期的に複数回クリックしても API は1回だけ呼ばれる', async () => {
    let resolveFollow: (v: unknown) => void = () => {}
    mockApi.mockImplementationOnce(() => new Promise((resolve) => { resolveFollow = resolve }))
    const wrapper = await mountButton()
    const button = wrapper.get('button')

    for (let i = 0; i < 8; i++) {
      void button.trigger('click')
    }
    await flushPromises()
    expect(mockApi).toHaveBeenCalledTimes(1)

    resolveFollow({ data: FOLLOW_RESPONSE })
    await flushPromises()
    expect(wrapper.emitted('followed')).toHaveLength(1)
  })

  it('再入ガード: 失敗後はガードが解除され再度送信できる', async () => {
    mockApi.mockRejectedValueOnce(Object.assign(new Error('Conflict'), { statusCode: 409 }))
    mockApi.mockResolvedValueOnce({ data: FOLLOW_RESPONSE })
    const wrapper = await mountButton()

    await wrapper.get('button').trigger('click')
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(mockApi).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('followed')).toHaveLength(1)
  })
})
