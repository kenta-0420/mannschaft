import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ScorekeeperManager from '~/components/tournament/ScorekeeperManager.vue'
import type { ScorekeeperResponse } from '~/types/tournament'
import type { MemberCardListItem } from '~/types/member-card'

/**
 * F08.7 順位UI ③: ScorekeeperManager.vue ユニットテスト。
 *
 * 検証観点:
 *   SK-UI-001: マウント時に一覧を取得して各 userId を表示する
 *   SK-UI-002: 一覧 0 件で空状態メッセージを出す
 *   SK-UI-003: userId 直接入力（フォールバック）で指名すると addScorekeeper → 再取得
 *   SK-UI-004: 不正な userId 入力では addScorekeeper を呼ばず warn する
 *   SK-UI-005: 既存指名済み userId は重複として info を出し POST しない
 *   SK-UI-006: 解除ボタンで removeScorekeeper を呼ぶ
 *   SK-UI-007: 一覧に displayName を表示する（userId フォールバックでない本格氏名表示）
 *   SK-UI-008: 氏名検索で候補を取得し、候補クリックで該当 userId を addScorekeeper
 *   SK-UI-009: 候補リストから既指名ユーザーは除外される
 */

const listScorekeepers = vi.fn()
const addScorekeeper = vi.fn()
const removeScorekeeper = vi.fn()
const searchOrgMembers = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyInfo = vi.fn()
const notifyWarn = vi.fn()

vi.mock('~/composables/tournament/useTournamentScorekeepers', () => ({
  useTournamentScorekeepers: () => ({ listScorekeepers, addScorekeeper, removeScorekeeper }),
}))

vi.mock('~/composables/useMemberCardApi', () => ({
  useMemberCardApi: () => ({ searchOrgMembers }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: notifySuccess,
    error: notifyError,
    info: notifyInfo,
    warn: notifyWarn,
  }),
}))

function sk(userId: number, id = `sk-${userId}`, displayName?: string): ScorekeeperResponse {
  return { id, tournamentId: 100, userId, displayName, createdBy: 1, createdAt: '2026-06-12T00:00:00' }
}

function card(id: number, userId: number, displayName: string): MemberCardListItem {
  return {
    id,
    userId,
    cardNumber: `C-${id}`,
    displayName,
    status: 'ACTIVE',
    issuedAt: '2026-01-01T00:00:00',
    lastCheckinAt: null,
    checkinCount: 0,
  }
}

const PROPS = { orgId: 'org-1', tournamentId: 100 }

describe('ScorekeeperManager.vue', () => {
  beforeEach(() => {
    listScorekeepers.mockReset()
    addScorekeeper.mockReset()
    removeScorekeeper.mockReset()
    searchOrgMembers.mockReset()
    notifySuccess.mockReset()
    notifyError.mockReset()
    notifyInfo.mockReset()
    notifyWarn.mockReset()
    listScorekeepers.mockResolvedValue({ data: [] })
    addScorekeeper.mockResolvedValue({ data: sk(42) })
    removeScorekeeper.mockResolvedValue(undefined)
    searchOrgMembers.mockResolvedValue([])
  })

  it('SK-UI-001: マウント時に一覧を取得して各 userId を表示する', async () => {
    listScorekeepers.mockResolvedValueOnce({ data: [sk(9), sk(10)] })
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    expect(listScorekeepers).toHaveBeenCalledWith('org-1', 100)
    // 指名一覧の各行（解除ボタンを持つ li）。候補リストは別 ul のため button 有無で区別する。
    const items = wrapper.findAll('li').filter((li) => li.find('button').exists())
    expect(items).toHaveLength(2)
  })

  it('SK-UI-002: 一覧 0 件で空状態メッセージを出す', async () => {
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    const items = wrapper.findAll('li').filter((li) => li.find('button').exists())
    expect(items).toHaveLength(0)
    expect(wrapper.text().length).toBeGreaterThan(0)
  })

  it('SK-UI-003: userId 直接入力で指名すると addScorekeeper → 再取得', async () => {
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()
    listScorekeepers.mockClear()

    await wrapper.find('input#scorekeeper-user-id').setValue('42')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(addScorekeeper).toHaveBeenCalledWith('org-1', 100, 42)
    expect(notifySuccess).toHaveBeenCalled()
    expect(listScorekeepers).toHaveBeenCalledWith('org-1', 100)
  })

  it('SK-UI-004: 不正な userId 入力では addScorekeeper を呼ばず warn する', async () => {
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    await wrapper.find('input#scorekeeper-user-id').setValue('abc')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(addScorekeeper).not.toHaveBeenCalled()
    expect(notifyWarn).toHaveBeenCalled()
  })

  it('SK-UI-005: 既存指名済み userId は重複として info を出し POST しない', async () => {
    listScorekeepers.mockResolvedValueOnce({ data: [sk(7)] })
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    await wrapper.find('input#scorekeeper-user-id').setValue('7')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(addScorekeeper).not.toHaveBeenCalled()
    expect(notifyInfo).toHaveBeenCalled()
  })

  it('SK-UI-006: 解除ボタンで removeScorekeeper を呼ぶ', async () => {
    listScorekeepers.mockResolvedValueOnce({ data: [sk(9, 'sk-id-9')] })
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    const removeBtn = wrapper.findAll('li').find((li) => li.find('button').exists())!.find('button')
    await removeBtn.trigger('click')
    await flushPromises()

    expect(removeScorekeeper).toHaveBeenCalledWith('org-1', 100, 'sk-id-9')
    expect(notifySuccess).toHaveBeenCalled()
  })

  it('SK-UI-007: 一覧に displayName を本格氏名として表示する', async () => {
    listScorekeepers.mockResolvedValueOnce({ data: [sk(9, 'sk-9', '田中 一郎')] })
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    expect(wrapper.text()).toContain('田中 一郎')
  })

  it('SK-UI-008: 氏名検索で候補を取得し、候補クリックで該当 userId を addScorekeeper', async () => {
    searchOrgMembers.mockResolvedValueOnce([card(1, 55, '鈴木 次郎')])
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    await wrapper.find('input#scorekeeper-search').setValue('鈴木')
    await wrapper.find('input#scorekeeper-search').trigger('input')
    // debounce(300ms) を待つ
    await new Promise((r) => setTimeout(r, 350))
    await flushPromises()

    expect(searchOrgMembers).toHaveBeenCalledWith('org-1', { q: '鈴木', status: 'ACTIVE' })
    expect(wrapper.text()).toContain('鈴木 次郎')

    // 候補（mousedown でクリック）
    const candidate = wrapper.findAll('li').find((li) => li.text().includes('鈴木 次郎'))!
    await candidate.trigger('mousedown')
    await flushPromises()

    expect(addScorekeeper).toHaveBeenCalledWith('org-1', 100, 55)
    expect(notifySuccess).toHaveBeenCalled()
  })

  it('SK-UI-009: 候補リストから既指名ユーザーを除外する', async () => {
    listScorekeepers.mockResolvedValueOnce({ data: [sk(55, 'sk-55', '鈴木 次郎')] })
    searchOrgMembers.mockResolvedValueOnce([card(1, 55, '鈴木 次郎'), card(2, 66, '高橋 三郎')])
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    await wrapper.find('input#scorekeeper-search').setValue('郎')
    await wrapper.find('input#scorekeeper-search').trigger('input')
    await new Promise((r) => setTimeout(r, 350))
    await flushPromises()

    // 既指名の 55 は候補から消え、66 のみ候補として残る
    const candidateItems = wrapper.findAll('li').filter((li) => li.text().includes('#66'))
    expect(candidateItems.length).toBeGreaterThan(0)
    const dupCandidate = wrapper.findAll('li').filter((li) => li.text().includes('#55'))
    // #55 を含むのは「指名一覧」行のみ（候補からは除外）→ 1 件だけ
    expect(dupCandidate).toHaveLength(1)
  })
})
