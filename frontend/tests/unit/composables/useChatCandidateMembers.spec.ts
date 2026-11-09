import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useChatChannels } from '~/composables/chat/useChatChannels'
import { useChatCandidateMembers } from '~/composables/useChatCandidateMembers'
const api = vi.fn()
const getTeamMembers = vi.fn()
const getOrgMembers = vi.fn()
const listContacts = vi.fn()

mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useTeamApi', () => () => ({ getMembers: getTeamMembers }))
mockNuxtImport('useOrganizationApi', () => () => ({ getMembers: getOrgMembers }))
mockNuxtImport('useContactApi', () => () => ({ listContacts }))

beforeEach(() => {
  api.mockReset()
  getTeamMembers.mockReset()
  getOrgMembers.mockReset()
  listContacts.mockReset()
})

describe('useChatChannels.getOrCreateDm', () => {
  it('BE の正規窓口 POST /conversations へ userIds で送る', async () => {
    api.mockResolvedValue({ data: { id: 5 } })
    await useChatChannels().getOrCreateDm(42)
    expect(api).toHaveBeenCalledWith('/api/v1/chat/channels/conversations', {
      method: 'POST',
      body: { userIds: [42] },
    })
  })
})

describe('useChatCandidateMembers.loadCandidates', () => {
  it('チーム文脈ではチームメンバーを返す', async () => {
    getTeamMembers.mockResolvedValue({ data: [{ userId: 1 }] })
    const r = await useChatCandidateMembers().loadCandidates({ teamId: 't1' })
    expect(r).toEqual([{ userId: 1 }])
    expect(listContacts).not.toHaveBeenCalled()
  })

  it('組織文脈では組織メンバーを返す', async () => {
    getOrgMembers.mockResolvedValue({ data: [{ userId: 2 }] })
    const r = await useChatCandidateMembers().loadCandidates({ organizationId: 'o1' })
    expect(r).toEqual([{ userId: 2 }])
  })

  it('汎用画面（teamId/organizationId なし）では連絡先を全ページ辿って候補にする', async () => {
    listContacts
      .mockResolvedValueOnce({
        data: [
          {
            user: { id: 7, fullName: '山田太郎', contactHandle: null, avatarUrl: null },
            customName: null,
            addedAt: '2026-01-01T00:00:00',
          },
        ],
        meta: { nextCursor: 'c2', total: 2 },
      })
      .mockResolvedValueOnce({
        data: [
          {
            user: { id: 8, fullName: '佐藤花子', contactHandle: null, avatarUrl: null },
            customName: '花ちゃん',
            addedAt: '2026-01-02T00:00:00',
          },
        ],
        meta: { nextCursor: null, total: 2 },
      })
    const r = await useChatCandidateMembers().loadCandidates({})
    expect(r.map((m) => [m.userId, m.displayName])).toEqual([
      [7, '山田太郎'],
      [8, '花ちゃん'],
    ])
    expect(listContacts).toHaveBeenCalledTimes(2)
    expect(listContacts.mock.calls[1]?.[0]).toMatchObject({ cursor: 'c2' })
  })
})
