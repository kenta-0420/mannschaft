import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import TournamentDetailPage from '~/pages/organizations/[slug]/tournaments/[tId]/index.vue'

/**
 * CMP-260929-0654 導線の欠落の回帰: 大会詳細ページが部門一覧を別エンドポイントから取得し、
 * 先頭部門を初期選択して参加チームを読み込み、複数部門をタブで切り替えられること。
 *
 * 検証観点:
 *   DIV-001: 開いた時に getDivisions を呼び、先頭部門の参加チームを取得する
 *   DIV-002: 部門が複数ある場合は全部門分のタブを描画し、切り替えで該当部門の参加チームを取得する
 *   DIV-003: 部門が 0 件なら「部門が登録されていません」を出す
 *   DIV-004: 部門一覧の取得に失敗したらエラー通知とエラー表示を出す（空表示に畳まない）
 */

const getTournament = vi.fn()
const getDivisions = vi.fn()
const getParticipants = vi.fn()
const getEntrySummary = vi.fn()
const updateTournament = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-000009', tId: '12' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTournamentApi', () => () => ({
  getTournament,
  getDivisions,
  getParticipants,
  getEntrySummary,
  updateTournament,
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: notifyError }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: ref(true),
  loadPermissions: vi.fn(),
}))

const tournamentRes = {
  data: { content: { name: '大会A', format: 'LEAGUE' }, structure: { status: 'OPEN' } },
}

async function mountPage() {
  const wrapper = await mountSuspended(TournamentDetailPage)
  await flushPromises()
  return wrapper
}

describe('organizations/[slug]/tournaments/[tId]/index.vue 部門タブ', () => {
  beforeEach(() => {
    getTournament.mockReset()
    getDivisions.mockReset()
    getParticipants.mockReset()
    getEntrySummary.mockReset()
    notifyError.mockReset()
    getTournament.mockResolvedValue(tournamentRes)
    getParticipants.mockResolvedValue({ data: [] })
    getEntrySummary.mockResolvedValue({ summary: [] })
  })

  it('DIV-001: 部門一覧を取得し、先頭部門を初期選択して参加チームを読み込む', async () => {
    getDivisions.mockResolvedValue({ data: [{ id: 5, name: 'D1' }] })
    const wrapper = await mountPage()

    expect(getDivisions).toHaveBeenCalledWith('org-000009', 12)
    expect(getParticipants).toHaveBeenCalledWith('org-000009', 12, 5)
    expect(wrapper.find('[data-testid="tournament-division-tab-5"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('tournament.detail.noDivisions')
  })

  it('DIV-002: 複数部門は全部門分のタブを描画し、切り替えで該当部門の参加チームを取得する', async () => {
    getDivisions.mockResolvedValue({
      data: [
        { id: 5, name: 'D1' },
        { id: 6, name: 'D2' },
      ],
    })
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="tournament-division-tab-5"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tournament-division-tab-6"]').exists()).toBe(true)
    expect(getParticipants).toHaveBeenCalledTimes(1)
    expect(getParticipants).toHaveBeenLastCalledWith('org-000009', 12, 5)

    await wrapper.find('[data-testid="tournament-division-tab-6"]').trigger('click')
    await flushPromises()

    expect(getParticipants).toHaveBeenCalledTimes(2)
    expect(getParticipants).toHaveBeenLastCalledWith('org-000009', 12, 6)
  })

  it('DIV-003: 部門が 0 件なら「部門が登録されていません」を出す', async () => {
    getDivisions.mockResolvedValue({ data: [] })
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('tournament.detail.noDivisions')
    expect(getParticipants).not.toHaveBeenCalled()
  })

  it('DIV-004: 部門一覧の取得失敗はエラー通知とエラー表示を出す（空表示に畳まない）', async () => {
    getDivisions.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()

    expect(notifyError).toHaveBeenCalledWith('tournament.detail.divisionsLoadError')
    expect(wrapper.find('[data-testid="tournament-divisions-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('tournament.detail.noDivisions')
  })
})
