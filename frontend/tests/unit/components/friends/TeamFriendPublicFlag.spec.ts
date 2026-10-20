import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamFriendList from '~/components/friends/TeamFriendList.vue'

/**
 * CMP-261014-2033: フレンドチームの公開フラグは API では JSON キー "public" で返る
 * （BE TeamFriendView の @JsonProperty("public")、openapi の TeamFriendView.public）。
 * FE が isPublic を読んでいたため常に undefined → 常に「非公開」表示になっていた。
 *   AC-1 public:true → 「公開」バッジ・スイッチ ON / public:false → 「非公開」・OFF
 *   AC-2 切替で現在値の反対が PATCH され、成功後の再取得で表示が切り替わる（公開→非公開→公開）
 *   AC-3 Vue の prop 型警告（Invalid prop: isPublic ... Undefined）が出ない
 * 応答 mock は実際の API の JSON 形（public キー）で作る。
 */
type ApiFriend = {
  teamFriendId: number
  friendTeamId: number
  friendTeamName: string
  public: boolean
  establishedAt: string
}

const api = vi.hoisted(() => ({ listFriends: vi.fn(), setVisibility: vi.fn() }))

mockNuxtImport('useFriendTeamsApi', () => () => ({
  listFriends: api.listFriends,
  setVisibility: api.setVisibility,
  unfollow: vi.fn(),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: vi.fn(),
  warn: vi.fn(),
  info: vi.fn(),
}))

const props = { teamId: 'team-1', canEdit: true, canToggleVisibility: true }

function friend(id: number, isPublic: boolean): ApiFriend {
  return {
    teamFriendId: id,
    friendTeamId: id + 100,
    friendTeamName: `フレンド${id}`,
    public: isPublic,
    establishedAt: '2026-10-01T00:00:00',
  }
}

function respond(rows: ApiFriend[]) {
  return { data: rows, pagination: { totalElements: rows.length } }
}

async function mountList(canToggleVisibility = true) {
  const w = await mountSuspended(TeamFriendList, { props: { ...props, canToggleVisibility } })
  await flushPromises()
  return w
}

function badgeText(w: Awaited<ReturnType<typeof mountList>>, index: number): string {
  return w.findAll('.p-tag')[index]?.text() ?? ''
}

function switchInputs(w: Awaited<ReturnType<typeof mountList>>) {
  return w.findAll('input[type="checkbox"]')
}

describe('components/friends — 公開フラグ(public キー)の表示と切替', () => {
  let warn: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    api.listFriends.mockReset()
    api.setVisibility.mockReset()
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    warn.mockRestore()
  })

  it('AC-1: public:true は「公開」バッジ・スイッチ ON、public:false は「非公開」・OFF', async () => {
    api.listFriends.mockResolvedValue(respond([friend(1, true), friend(2, false)]))
    const w = await mountList()
    expect(badgeText(w, 0)).toBe('公開')
    expect(badgeText(w, 1)).toBe('非公開')
    const inputs = switchInputs(w)
    expect(inputs).toHaveLength(2)
    expect((inputs[0]!.element as HTMLInputElement).checked).toBe(true)
    expect((inputs[1]!.element as HTMLInputElement).checked).toBe(false)
  })

  it('AC-1: 操作権限なし(SUPPORTER 相当)ではスイッチが無効のまま公開状態を表示する', async () => {
    api.listFriends.mockResolvedValue(respond([friend(1, true)]))
    const w = await mountList(false)
    expect(badgeText(w, 0)).toBe('公開')
    const input = switchInputs(w)[0]!.element as HTMLInputElement
    expect(input.checked).toBe(true)
    expect(input.disabled).toBe(true)
  })

  it('AC-2: 公開→非公開→公開と切替でき、反対の値が PATCH され再取得で表示が変わる', async () => {
    let current = true
    api.listFriends.mockImplementation(async () => respond([friend(1, current)]))
    api.setVisibility.mockImplementation(async (_t: string, _id: number, req: { isPublic: boolean }) => {
      current = req.isPublic
    })
    const w = await mountList()
    expect(badgeText(w, 0)).toBe('公開')

    await switchInputs(w)[0]!.setValue(false)
    await flushPromises()
    expect(api.setVisibility).toHaveBeenLastCalledWith('team-1', 1, { isPublic: false })
    expect(badgeText(w, 0)).toBe('非公開')

    await switchInputs(w)[0]!.setValue(true)
    await flushPromises()
    expect(api.setVisibility).toHaveBeenLastCalledWith('team-1', 1, { isPublic: true })
    expect(badgeText(w, 0)).toBe('公開')
    expect(api.setVisibility).toHaveBeenCalledTimes(2)
  })

  it('AC-3: Vue の prop 型警告（isPublic が undefined）が出ない', async () => {
    api.listFriends.mockResolvedValue(respond([friend(1, true), friend(2, false)]))
    await mountList()
    const messages = warn.mock.calls.map(args => args.map(String).join(' '))
    expect(messages.filter(m => m.includes('Invalid prop'))).toEqual([])
  })
})
