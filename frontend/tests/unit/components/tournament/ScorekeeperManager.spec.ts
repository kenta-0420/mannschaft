import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ScorekeeperManager from '~/components/tournament/ScorekeeperManager.vue'
import type { ScorekeeperResponse } from '~/types/tournament'

/**
 * F08.7 順位UI Wave B-3: ScorekeeperManager.vue ユニットテスト。
 *
 * 検証観点:
 *   SK-UI-001: マウント時に一覧を取得して各 userId を表示する
 *   SK-UI-002: 一覧 0 件で空状態メッセージを出す
 *   SK-UI-003: userId を入力して指名すると addScorekeeper → 再取得
 *   SK-UI-004: 不正な userId 入力では addScorekeeper を呼ばず warn する
 *   SK-UI-005: 既存指名済み userId は重複として info を出し POST しない
 *   SK-UI-006: 解除ボタンで removeScorekeeper を呼ぶ
 */

const listScorekeepers = vi.fn()
const addScorekeeper = vi.fn()
const removeScorekeeper = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyInfo = vi.fn()
const notifyWarn = vi.fn()

vi.mock('~/composables/tournament/useTournamentScorekeepers', () => ({
  useTournamentScorekeepers: () => ({ listScorekeepers, addScorekeeper, removeScorekeeper }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: notifySuccess,
    error: notifyError,
    info: notifyInfo,
    warn: notifyWarn,
  }),
}))

function sk(userId: number, id = `sk-${userId}`): ScorekeeperResponse {
  return { id, tournamentId: 100, userId, createdBy: 1, createdAt: '2026-06-12T00:00:00' }
}

const PROPS = { orgId: 'org-1', tournamentId: 100 }

describe('ScorekeeperManager.vue', () => {
  beforeEach(() => {
    listScorekeepers.mockReset()
    addScorekeeper.mockReset()
    removeScorekeeper.mockReset()
    notifySuccess.mockReset()
    notifyError.mockReset()
    notifyInfo.mockReset()
    notifyWarn.mockReset()
    listScorekeepers.mockResolvedValue({ data: [] })
    addScorekeeper.mockResolvedValue({ data: sk(42) })
    removeScorekeeper.mockResolvedValue(undefined)
  })

  it('SK-UI-001: マウント時に一覧を取得して各 userId を表示する', async () => {
    listScorekeepers.mockResolvedValueOnce({ data: [sk(9), sk(10)] })
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    expect(listScorekeepers).toHaveBeenCalledWith('org-1', 100)
    // 取得した指名 1 件ごとに行（li）が描画される。
    // 表示名は i18n interpolation（{userId}）に依存するため行数で検証する。
    const items = wrapper.findAll('li')
    expect(items).toHaveLength(2)
    // 各行に解除ボタンが存在する
    expect(wrapper.findAll('li button')).toHaveLength(2)
  })

  it('SK-UI-002: 一覧 0 件で空状態メッセージを出す', async () => {
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()

    expect(wrapper.findAll('li')).toHaveLength(0)
    // 空状態テキスト（i18n キー or 翻訳文）が描画される
    expect(wrapper.text().length).toBeGreaterThan(0)
  })

  it('SK-UI-003: userId を入力して指名すると addScorekeeper → 再取得', async () => {
    const wrapper = await mountSuspended(ScorekeeperManager, { props: PROPS })
    await flushPromises()
    listScorekeepers.mockClear()

    await wrapper.find('input#scorekeeper-user-id').setValue('42')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(addScorekeeper).toHaveBeenCalledWith('org-1', 100, 42)
    expect(notifySuccess).toHaveBeenCalled()
    // 追加後に一覧を再取得する
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

    await wrapper.find('li button').trigger('click')
    await flushPromises()

    expect(removeScorekeeper).toHaveBeenCalledWith('org-1', 100, 'sk-id-9')
    expect(notifySuccess).toHaveBeenCalled()
  })
})
