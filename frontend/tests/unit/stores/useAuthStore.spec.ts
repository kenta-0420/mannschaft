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
// 【事実】この spec は以前 useChatTabsStore / disarmProactiveRefresh を `vi.stubGlobal` で
// モックしていたが上記の理由で差し替わっておらず、実体が走っていた。
//
// 【事実】その状態の CI で、全テストが緑にもかかわらず未処理 rejection により vitest が
// exit 1 になる事象が間欠的に発生していた。vue-router の pending navigation が
// テストファイルの寿命を超えて残り、happy-dom 環境の破棄後に finalizeNavigation
// （`vue-router.mjs:1385` の `const state = !isBrowser ? {} : history.state` という
// 裸のグローバル `history` 参照）へ到達して `ReferenceError: history is not defined` になる。
//
// 【推測・未確定】その pending navigation を誰が開始したのかは特定できていない。
// 実 pinia ストア生成に伴う Nuxt アプリ／router の初期化が疑わしいが、確認できていない。
// 実効的だった差分は「実体を走らせなくしたこと」であり、真の誘発点は未特定のままである。
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
    expect(navigateToMock).toHaveBeenCalledWith('/login')
  })
})
