import { describe, it, expect, expectTypeOf } from 'vitest'
import type { components } from '~/types/generated'
import type { TeamFriendView } from '~/types/friends'

/**
 * CMP-261014-2033 AC-4: 手動型 TeamFriendView が API 契約（生成型）とキー名で食い違わない。
 * 生成型のキーは API が実際に返す JSON キー。公開フラグは "public"（isPublic ではない）。
 * 型の一致は typecheck、キー集合の一致は実行時（キー名リスト経由）の双方で検査する。
 */
type Generated = components['schemas']['TeamFriendView']

describe('types/friends.ts TeamFriendView と生成型の整合', () => {
  it('キー集合が生成型と完全に一致する（型レベル）', () => {
    expectTypeOf<keyof TeamFriendView>().toEqualTypeOf<keyof Generated>()
  })

  it('公開フラグのキーは public（isPublic は存在しない）', () => {
    expectTypeOf<TeamFriendView['public']>().toEqualTypeOf<boolean>()
    expectTypeOf<TeamFriendView>().not.toHaveProperty('isPublic')
    const sample: TeamFriendView = {
      teamFriendId: 1,
      friendTeamId: 2,
      friendTeamName: 'x',
      public: true,
      establishedAt: '2026-10-01T00:00:00',
    }
    expect(Object.keys(sample).sort()).toEqual(
      ['establishedAt', 'friendTeamId', 'friendTeamName', 'public', 'teamFriendId'],
    )
  })
})
