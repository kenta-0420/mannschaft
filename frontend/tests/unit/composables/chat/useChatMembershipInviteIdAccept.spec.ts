/**
 * 試練（CMP-260826-1457 W1 P1・AC-12）: 承諾型招待の承諾・辞退 API は招待 ID（tokenId）で呼ぶ。
 *
 * 実装側が満たすべき契約（BE の仮契約と同じ）:
 * - `joinInvite(tokenId: number)` → POST `/api/v1/invite/invitations/${tokenId}/accept`（body `{}`）
 * - `declineInvite(tokenId: number)` → POST `/api/v1/invite/invitations/${tokenId}/decline`
 * - 平文 token を URL に載せる `/api/v1/invite/{token}/join|decline` は、チャットの承諾型招待からは呼ばない。
 *
 * red の理由: いまの composable は引数を平文 token として `/api/v1/invite/{token}/join|decline` を呼ぶ。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useChatMembershipInvite } from '~/composables/chat/useChatMembershipInvite'

const { mockApi } = vi.hoisted(() => ({ mockApi: vi.fn() }))

mockNuxtImport('useApi', () => () => mockApi)

/** 実装後のシグネチャ（tokenId: number）で呼ぶための型。現行の (token: string) とは別に試験側で宣言する。 */
interface IdBasedInviteActions {
  joinInvite: (tokenId: number) => Promise<void>
  declineInvite: (tokenId: number) => Promise<void>
}

function idBased(): IdBasedInviteActions {
  return useChatMembershipInvite() as unknown as IdBasedInviteActions
}

beforeEach(() => {
  mockApi.mockReset()
  mockApi.mockResolvedValue(undefined)
})

describe('useChatMembershipInvite: ID で承諾・辞退する（CMP-260826-1457 W1 P1）', () => {
  it('joinInvite(tokenId) は ID 承諾の入口を POST する', async () => {
    await idBased().joinInvite(4242)

    expect(mockApi).toHaveBeenCalledTimes(1)
    const [url, options] = mockApi.mock.calls[0] as [string, { method?: string }]
    expect(url).toBe('/api/v1/invite/invitations/4242/accept')
    expect(options?.method).toBe('POST')
  })

  it('declineInvite(tokenId) は ID 辞退の入口を POST する', async () => {
    await idBased().declineInvite(4242)

    expect(mockApi).toHaveBeenCalledTimes(1)
    const [url, options] = mockApi.mock.calls[0] as [string, { method?: string }]
    expect(url).toBe('/api/v1/invite/invitations/4242/decline')
    expect(options?.method).toBe('POST')
  })
})
