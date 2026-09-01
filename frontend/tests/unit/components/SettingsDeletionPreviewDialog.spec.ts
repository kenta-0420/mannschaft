import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import SettingsDeletionPreviewDialog from '~/components/settings/SettingsDeletionPreviewDialog.vue'
import type { DeletionPreviewResponse } from '~/composables/useGdprApi'

/**
 * 柱①「ADMINゼロ根治」FE — SettingsDeletionPreviewDialog.vue のユニットテスト。
 *
 * 正本: docs/architecture/account_purge_last_admin_succession.md §14。
 *
 * - lastAdminScopes が1件でもあれば削除ボタンが disabled になり、案内が出る
 * - lastAdminScopes が空なら削除ボタンは有効
 * - 各スコープ行に「後任を指名する」「アーカイブする」導線ボタンが出る
 *
 * <p><b>プレビュー取得のトリガー</b>: 本体は {@code watch(() => props.visible, ...)}（immediate
 * なし）で {@code visible: false → true} の遷移を検知して {@code loadPreview()} を呼ぶ。
 * 実運用（account.vue）でも常に false から開始するため、テストも
 * {@code visible: false} でマウントしてから {@code setProps({ visible: true })} で開く
 * （props 初期値を true にすると watch が発火せず、実運用と異なる偽陽性/偽陰性を生む）。</p>
 *
 * <p><b>Teleport 対応</b>: PrimeVue Dialog は {@code <Teleport to="body">} でレンダリングされるため
 * {@code document.body.querySelector} で要素を取得する（ActionMemoEditDialog.spec.ts と同じ流儀）。</p>
 */

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}
function getByTestId<T extends Element = HTMLElement>(testId: string): T {
  const el = findByTestId<T>(testId)
  if (!el) {
    throw new Error(`[data-testid="${testId}"] が見つかりません`)
  }
  return el
}

async function flush(times = 3) {
  for (let i = 0; i < times; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

const getDeletionPreviewMock = vi.fn()

vi.mock('~/composables/useGdprApi', () => ({
  useGdprApi: () => ({
    getDeletionPreview: getDeletionPreviewMock,
  }),
}))

const navigateToMock = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigateToMock(...args))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function makePreview(overrides: Partial<DeletionPreviewResponse> = {}): DeletionPreviewResponse {
  return {
    retentionDays: 30,
    dataSummary: { charts: 3 },
    anonymized: [],
    lastAdminScopes: [],
    warnings: [],
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  getDeletionPreviewMock.mockReset()
  navigateToMock.mockReset()
})

afterEach(() => {
  const dialog = findByTestId('settings-deletion-preview-dialog')
  dialog?.parentElement?.removeChild(dialog)
})

describe('SettingsDeletionPreviewDialog.vue', () => {
  it('lastAdminScopes が空なら削除ボタンは有効', async () => {
    getDeletionPreviewMock.mockResolvedValueOnce({ data: makePreview() })
    const wrapper = await mountSuspended(SettingsDeletionPreviewDialog, {
      props: { visible: false, hasPassword: false },
    })
    await wrapper.setProps({ visible: true })
    await flush()

    expect(getDeletionPreviewMock).toHaveBeenCalledTimes(1)
    expect(findByTestId('settings-deletion-preview-last-admin-block')).toBeNull()
    const deleteBtn = getByTestId<HTMLButtonElement>('settings-deletion-preview-delete-button')
    expect(deleteBtn.disabled).toBe(false)
  })

  it('lastAdminScopes が1件でもあれば削除ボタンが disabled になり、案内文が出る', async () => {
    getDeletionPreviewMock.mockResolvedValueOnce({
      data: makePreview({
        lastAdminScopes: [
          { scopeType: 'TEAM', scopeId: 101, scopeName: 'U-15チーム', otherMembersCount: 3 },
        ],
      }),
    })
    const wrapper = await mountSuspended(SettingsDeletionPreviewDialog, {
      props: { visible: false, hasPassword: false },
    })
    await wrapper.setProps({ visible: true })
    await flush()

    expect(findByTestId('settings-deletion-preview-last-admin-block')).not.toBeNull()
    const deleteBtn = getByTestId<HTMLButtonElement>('settings-deletion-preview-delete-button')
    expect(deleteBtn.disabled).toBe(true)
    expect(findByTestId('settings-deletion-preview-blocked-notice')).not.toBeNull()
  })

  it('スコープ行に後任指名・アーカイブ導線ボタンがあり、クリックで対応する一覧ページへ遷移する', async () => {
    getDeletionPreviewMock.mockResolvedValueOnce({
      data: makePreview({
        lastAdminScopes: [
          { scopeType: 'ORGANIZATION', scopeId: 55, scopeName: 'テスト法人', otherMembersCount: 1 },
        ],
      }),
    })
    const wrapper = await mountSuspended(SettingsDeletionPreviewDialog, {
      props: { visible: false, hasPassword: false },
    })
    await wrapper.setProps({ visible: true })
    await flush()

    const transferBtn = getByTestId<HTMLButtonElement>('settings-deletion-preview-transfer-ORGANIZATION-55')
    const archiveBtn = getByTestId<HTMLButtonElement>('settings-deletion-preview-archive-ORGANIZATION-55')
    expect(transferBtn).not.toBeNull()
    expect(archiveBtn).not.toBeNull()

    transferBtn.click()
    await flush()
    expect(navigateToMock).toHaveBeenCalledWith('/organizations')
  })
})
