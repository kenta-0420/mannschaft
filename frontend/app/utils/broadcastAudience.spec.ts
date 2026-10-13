import { describe, expect, it } from 'vitest'
import {
  buildAudienceFields,
  formatAudienceLabel,
  hasAudienceSelection,
} from './broadcastAudience'
import type { WizardFormState } from '~/types/announcement_broadcast'

/** F01.2.1 8-E: 宛先指定 → リクエスト項目の変換と送信履歴の宛先ラベル（AC-H06/H17 の純粋部分） */

function state(partial: Partial<WizardFormState> = {}): WizardFormState {
  return {
    step: 1,
    targetRole: 'MEMBERS_AND_ABOVE',
    targetMode: 'ALL',
    targetTeamIds: null,
    targetGroupIds: [],
    targetGroupRange: null,
    includeUnassigned: false,
    templateEmptied: false,
    selectedChannel: null,
    templateId: null,
    priority: 'NORMAL',
    expiresAt: null,
    content: {},
    ...partial,
  }
}

const t = (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key)

describe('buildAudienceFields（送信値）', () => {
  it('すべてのチームは制限なし（グループ項目を送らない）', () => {
    expect(buildAudienceFields(state())).toEqual({ targetTeamIds: null })
  })

  it('チームを選ぶは targetTeamIds だけを送る（BROADCAST_011 を避ける）', () => {
    const f = buildAudienceFields(state({ targetMode: 'TEAMS', targetTeamIds: ['1', '2'], targetGroupIds: ['g1'], includeUnassigned: true }))
    expect(f).toEqual({ targetTeamIds: ['1', '2'] })
  })

  it('チームグループで選ぶは個別・範囲・未分類を送り、targetTeamIds は null', () => {
    const f = buildAudienceFields(state({
      targetMode: 'GROUPS',
      targetGroupIds: ['g1'],
      targetGroupRange: { fromGroupId: null, toGroupId: 'g3' },
      includeUnassigned: true,
    }))
    expect(f).toEqual({
      targetTeamIds: null,
      targetGroupIds: ['g1'],
      targetGroupRange: { fromGroupId: null, toGroupId: 'g3' },
      includeUnassigned: true,
    })
  })

  it('グループ指定で個別・範囲が空なら targetGroupIds / targetGroupRange は送らない', () => {
    const f = buildAudienceFields(state({ targetMode: 'GROUPS', includeUnassigned: true }))
    expect(f).toEqual({ targetTeamIds: null, includeUnassigned: true })
  })
})

describe('hasAudienceSelection（0件の入力で次へを押させない前提）', () => {
  it('すべてのチームは常に選択済み', () => {
    expect(hasAudienceSelection(state())).toBe(true)
  })
  it('グループ指定で何も選んでいなければ未選択', () => {
    expect(hasAudienceSelection(state({ targetMode: 'GROUPS' }))).toBe(false)
  })
  it.each([
    ['個別', { targetGroupIds: ['g1'] }],
    ['範囲', { targetGroupRange: { fromGroupId: 'g1', toGroupId: null } }],
    ['未分類', { includeUnassigned: true }],
  ])('グループ指定で%sだけでも選択済み', (_n, p) => {
    expect(hasAudienceSelection(state({ targetMode: 'GROUPS', ...p }))).toBe(true)
  })
  it('チームを選ぶで空配列は未選択', () => {
    expect(hasAudienceSelection(state({ targetMode: 'TEAMS', targetTeamIds: [] }))).toBe(false)
  })
})

describe('formatAudienceLabel（送信履歴の宛先。送信時の名前で残る）', () => {
  it('以前の範囲', () => {
    const label = formatAudienceLabel({
      mode: 'GROUPS',
      groups: [{ id: 'g1', name: '平成18年度' }, { id: 'g2', name: '平成20年度' }],
      range: { toGroupId: 'g2', toGroupName: '平成20年度' },
      includeUnassigned: false,
      teamCount: 12,
      directMemberCount: 3,
    }, t)
    expect(label).toBe('announcement.target_range_up_to:{"name":"平成20年度"}')
  })
  it('以降の範囲＋未分類', () => {
    const label = formatAudienceLabel({
      mode: 'GROUPS', groups: [], range: { fromGroupId: 'g2', fromGroupName: '平成20年度' }, includeUnassigned: true, teamCount: 5,
    }, t)
    expect(label).toBe('announcement.target_range_from:{"name":"平成20年度"}、announcement.target_include_unassigned')
  })
  it('A〜B', () => {
    const label = formatAudienceLabel({
      mode: 'GROUPS', groups: [], range: { fromGroupId: 'a', fromGroupName: 'A', toGroupId: 'b', toGroupName: 'B' }, includeUnassigned: false, teamCount: 1,
    }, t)
    expect(label).toBe('announcement.target_range_between:{"from":"A","to":"B"}')
  })
  it('範囲なしは個別グループ名を並べる', () => {
    const label = formatAudienceLabel({
      mode: 'GROUPS', groups: [{ id: 'g1', name: '北地区' }, { id: 'g2', name: '南地区' }], includeUnassigned: false, teamCount: 4,
    }, t)
    expect(label).toBe('北地区、南地区')
  })
  it('チームを選ぶモード', () => {
    expect(formatAudienceLabel({ mode: 'TEAMS', groups: [], includeUnassigned: false, teamCount: 2 }, t)).toBe('announcement.target_mode_teams')
  })
  it('範囲と範囲外の個別グループの併用は和集合（範囲＋範囲外のグループ名）で表す', () => {
    const label = formatAudienceLabel({
      mode: 'GROUPS',
      groups: [{ id: 'g1', name: 'G1' }, { id: 'g2', name: 'G2' }, { id: 'g4', name: 'G4' }],
      range: { toGroupId: 'g2', toGroupName: 'G2' },
      includeUnassigned: true,
      teamCount: 9,
    }, t)
    expect(label).toBe('announcement.target_range_up_to:{"name":"G2"}、G4、announcement.target_include_unassigned')
  })
  it('以降の範囲では範囲より前の個別グループが追加分になる', () => {
    const label = formatAudienceLabel({
      mode: 'GROUPS',
      groups: [{ id: 'g1', name: 'G1' }, { id: 'g3', name: 'G3' }, { id: 'g4', name: 'G4' }],
      range: { fromGroupId: 'g3', fromGroupName: 'G3' },
      includeUnassigned: false,
      teamCount: 9,
    }, t)
    expect(label).toBe('announcement.target_range_from:{"name":"G3"}、G1')
  })
})
