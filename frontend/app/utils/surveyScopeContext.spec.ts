// @vitest-environment node
import { describe, expect, it } from 'vitest'
import {
  normalizeSurveyScopeName,
  resolveSurveyManagementContext,
  surveyDetailPageKey,
} from './surveyScopeContext'

describe('surveyScopeContext', () => {
  it('queryを含むfullPathをページkeyにして別スコープ遷移時に再生成させる', () => {
    const first = surveyDetailPageKey({
      fullPath: '/surveys/42?scope=team&scopeId=team-a',
    })
    const second = surveyDetailPageKey({
      fullPath: '/surveys/42?scope=team&scopeId=team-b',
    })

    expect(second).toBe('/surveys/42?scope=team&scopeId=team-b')
    expect(second).not.toBe(first)
  })

  describe('normalizeSurveyScopeName', () => {
    it('取得した名称の前後空白を除去する', () => {
      expect(normalizeSurveyScopeName('  FC東京U-18  ')).toBe('FC東京U-18')
    })

    it('長い名称を省略せず保持する', () => {
      const longName = '地域連携スポーツクラブ'.repeat(20)

      expect(normalizeSurveyScopeName(longName)).toBe(longName)
    })

    it.each([undefined, null, '', '   '])('名称未取得時はslugへ代替せずnullを返す: %s', (name) => {
      expect(normalizeSurveyScopeName(name)).toBeNull()
    })
  })

  describe('resolveSurveyManagementContext', () => {
    it('管理不可ならロールにかかわらず管理文脈を表示しない', () => {
      expect(
        resolveSurveyManagementContext({
          canManage: false,
          roleName: 'DEPUTY_ADMIN',
          permissions: ['MANAGE_SURVEYS'],
          isCreator: false,
        }),
      ).toBeNull()
    })

    it.each(['ADMIN', 'SYSTEM_ADMIN'] as const)('%sは管理者として表示する', (roleName) => {
      expect(
        resolveSurveyManagementContext({
          canManage: true,
          roleName,
          permissions: [],
          isCreator: false,
        }),
      ).toBe('admin')
    })

    it('MANAGE_SURVEYSを持つDEPUTY_ADMINは委任権限として表示する', () => {
      expect(
        resolveSurveyManagementContext({
          canManage: true,
          roleName: 'DEPUTY_ADMIN',
          permissions: ['MANAGE_SURVEYS'],
          isCreator: false,
        }),
      ).toBe('delegated')
    })

    it('作成者高速パスは作成者として表示する', () => {
      expect(
        resolveSurveyManagementContext({
          canManage: true,
          roleName: 'MEMBER',
          permissions: [],
          isCreator: true,
        }),
      ).toBe('creator')
    })

    it('権限取得失敗時は委任と断定せず汎用表示に倒す', () => {
      expect(
        resolveSurveyManagementContext({
          canManage: true,
          roleName: null,
          permissions: [],
          isCreator: false,
        }),
      ).toBe('authorized')
    })
  })
})
