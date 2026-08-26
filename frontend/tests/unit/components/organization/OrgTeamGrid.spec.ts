import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import OrgTeamGrid from '~/components/organization/OrgTeamGrid.vue'
import type { OrgTeam } from '~/types/organization'

/**
 * OrgTeamGrid.vue 回帰テスト（slug 移行のリンク 404 根治・検知網）
 *
 * 背景:
 *   チーム/組織の URL は slug 統一が正準（`/teams/{slug}`）。
 *   以前は BIGINT の `team.id` を URL に流しており、組織配下チーム一覧の
 *   カードクリックが全件 404 になっていた（HIGH ギャップ）。
 *   #1528 で `OrgTeam` に slug を追加しリンクを `team.slug` 化済み。
 *   本テストはその退行（BIGINT id への巻き戻り / undefined リンク）を機械検知する。
 *
 * 観点:
 *   OTG-001: カードクリックで `/teams/{slug}` へ遷移する（BIGINT id ではない）
 *   OTG-002: slug 欠落時は `/teams/undefined` を出さずナビゲートしない（防御ガード）
 */

const mockNavigate = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigate(...args))

function makeTeam(overrides: Partial<OrgTeam> = {}): OrgTeam {
  return {
    id: 12345,
    slug: 'tokyo-fc',
    name: '東京FC',
    nickname1: null,
    iconUrl: null,
    template: 'SOCCER',
    memberCount: 11,
    ...overrides,
  }
}

describe('OrgTeamGrid.vue（slug リンク回帰）', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
  })

  it('OTG-001: カードクリックで slug ベースの URL へ遷移する（BIGINT id を URL に使わない）', async () => {
    const team = makeTeam({ id: 99999, slug: 'osaka-united' })
    const wrapper = await mountSuspended(OrgTeamGrid, {
      props: { teams: [team] },
    })

    const card = wrapper.find('.cursor-pointer')
    expect(card.exists()).toBe(true)
    await card.trigger('click')

    expect(mockNavigate).toHaveBeenCalledTimes(1)
    expect(mockNavigate).toHaveBeenCalledWith('/teams/osaka-united')
    // BIGINT id（99999）が URL に混入していないことを明示
    expect(mockNavigate).not.toHaveBeenCalledWith('/teams/99999')
  })

  it('OTG-002: slug 欠落時は /teams/undefined を出さずナビゲートしない（防御ガード）', async () => {
    // slug は本来必ず来る設計だが、欠落しても 404 URL を生成しないことを保証する
    const team = makeTeam({ slug: undefined as unknown as string })
    const wrapper = await mountSuspended(OrgTeamGrid, {
      props: { teams: [team] },
    })

    const card = wrapper.find('.cursor-pointer')
    expect(card.exists()).toBe(true)
    await card.trigger('click')

    expect(mockNavigate).not.toHaveBeenCalled()
  })
})
