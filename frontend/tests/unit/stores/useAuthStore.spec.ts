import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '../../../app/stores/useAuthStore'

const { teamClear, organizationClear, chatClear, navigateToMock, disarmMock } = vi.hoisted(() => ({
  teamClear: vi.fn(),
  organizationClear: vi.fn(),
  chatClear: vi.fn(),
  navigateToMock: vi.fn(),
  disarmMock: vi.fn(),
}))

vi.mock('../../../app/stores/useTeamStore', () => ({ useTeamStore: () => ({ clear: teamClear }) }))
vi.mock('../../../app/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ clear: organizationClear }) }))

// 自動 import（navigateTo / useChatTabsStore / disarmProactiveRefresh）は Nuxt のビルド時変換で
// 実モジュールからの import 文に展開されるため、`vi.stubGlobal` では差し替えられない
// （グローバル変数として参照されていない）。差し替えには mockNuxtImport を使うこと。
//
// とくに navigateTo を stubGlobal で「差し替えたつもり」になると実 navigateTo が走り、
// テストファイルの寿命を超えて vue-router のナビゲーションが保留のまま残る。happy-dom 環境が
// 破棄された後にそれが finalizeNavigation（`history.state` を参照）まで到達すると
// `ReferenceError: history is not defined` の未処理 rejection になり、全テストが緑でも
// vitest が exit 1 になる（CI で間欠的に発生していた）。
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigateToMock(...args))
mockNuxtImport('useChatTabsStore', () => () => ({ clearAll: chatClear }))
mockNuxtImport('disarmProactiveRefresh', () => () => disarmMock())

describe('useAuthStore.logout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    teamClear.mockClear()
    organizationClear.mockClear()
    chatClear.mockClear()
    navigateToMock.mockReset()
    disarmMock.mockReset()
  })

  it('ユーザー切替前に所属チーム・組織ストアを同期的に破棄する', async () => {
    await useAuthStore().logout()

    expect(teamClear).toHaveBeenCalledOnce()
    expect(organizationClear).toHaveBeenCalledOnce()
    expect(chatClear).toHaveBeenCalledOnce()
    expect(disarmMock).toHaveBeenCalledOnce()
    // 実 navigateTo が走っていない（＝モックが効いている）ことの担保でもある。
    // ここが落ちるときはナビゲーションが実ルーターへ抜けており、上記の未処理 rejection が再発する。
    expect(navigateToMock).toHaveBeenCalledWith('/login')
  })
})
