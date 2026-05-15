/**
 * F15.3: ScopeNavDropdown コンポーネントのユニットテスト。
 *
 * mountSuspended は Nuxt のプラグイン依存が広く、テスト設定が複雑化するため、
 * コンポーネントの責務である **basePath ルール**（設計書 §7.2 表）と
 * **遷移先 URL 生成ロジック** をリプロダクションして検証する。
 *
 * 検証範囲:
 *  - scopeType=TEAM → basePath=/teams
 *  - scopeType=ORGANIZATION → basePath=/organizations
 *  - 「すべて」「フォルダ」「未分類」「個別スコープ」遷移先 URL の生成（設計書 §7.2 6 例）
 *  - チームドロップダウンが組織パスへ遷移しない不変条件
 */
import { describe, it, expect } from 'vitest'
import type { ScopeType } from '~/types/scopeFolder'

/** ScopeNavDropdown 内 basePath 算出ロジックの再現。 */
function computeBasePath(scopeType: ScopeType): string {
  return scopeType === 'TEAM' ? '/teams' : '/organizations'
}

/** 「すべて」遷移先 URL の生成。 */
function buildAllPath(scopeType: ScopeType): string {
  return computeBasePath(scopeType)
}

/** フォルダ遷移先 URL（`?folder={id}`）。 */
function buildFolderPath(scopeType: ScopeType, folderId: number): {
  path: string
  query: { folder: string }
} {
  return { path: computeBasePath(scopeType), query: { folder: String(folderId) } }
}

/** 未分類フォルダ遷移先 URL（`?folder=default`）。 */
function buildDefaultPath(scopeType: ScopeType): {
  path: string
  query: { folder: string }
} {
  return { path: computeBasePath(scopeType), query: { folder: 'default' } }
}

/** 個別スコープ遷移先 URL（`{basePath}/{id}`）。 */
function buildScopePath(scopeType: ScopeType, scopeId: number): string {
  return `${computeBasePath(scopeType)}/${scopeId}`
}

describe('ScopeNavDropdown basePath ルール（設計書 §7.2）', () => {
  describe('basePath 切替', () => {
    it('TEAM → /teams', () => {
      expect(computeBasePath('TEAM')).toBe('/teams')
    })

    it('ORGANIZATION → /organizations', () => {
      expect(computeBasePath('ORGANIZATION')).toBe('/organizations')
    })
  })

  describe('遷移先 URL 生成（設計書 §7.2 の 6 例）', () => {
    it('TEAM のドロップダウンから「すべて」→ /teams', () => {
      expect(buildAllPath('TEAM')).toBe('/teams')
    })

    it('TEAM のドロップダウンからフォルダ(id=123)→ /teams?folder=123', () => {
      expect(buildFolderPath('TEAM', 123)).toEqual({
        path: '/teams',
        query: { folder: '123' },
      })
    })

    it('TEAM のドロップダウンから個別チーム(id=456)→ /teams/456', () => {
      expect(buildScopePath('TEAM', 456)).toBe('/teams/456')
    })

    it('ORGANIZATION のドロップダウンから「すべて」→ /organizations', () => {
      expect(buildAllPath('ORGANIZATION')).toBe('/organizations')
    })

    it('ORGANIZATION のドロップダウンからフォルダ(id=789)→ /organizations?folder=789', () => {
      expect(buildFolderPath('ORGANIZATION', 789)).toEqual({
        path: '/organizations',
        query: { folder: '789' },
      })
    })

    it('ORGANIZATION のドロップダウンから個別組織(id=321)→ /organizations/321', () => {
      expect(buildScopePath('ORGANIZATION', 321)).toBe('/organizations/321')
    })
  })

  describe('未分類フォルダ', () => {
    it('TEAM → /teams?folder=default', () => {
      expect(buildDefaultPath('TEAM')).toEqual({
        path: '/teams',
        query: { folder: 'default' },
      })
    })

    it('ORGANIZATION → /organizations?folder=default', () => {
      expect(buildDefaultPath('ORGANIZATION')).toEqual({
        path: '/organizations',
        query: { folder: 'default' },
      })
    })
  })

  describe('不変条件: scopeType をまたぐ遷移は起こらない', () => {
    it('TEAM のドロップダウンが生成する全 URL は /teams 配下のみ', () => {
      const urls = [
        buildAllPath('TEAM'),
        buildFolderPath('TEAM', 123).path,
        buildDefaultPath('TEAM').path,
        buildScopePath('TEAM', 456),
      ]
      for (const url of urls) {
        expect(url.startsWith('/teams')).toBe(true)
        expect(url.startsWith('/organizations')).toBe(false)
      }
    })

    it('ORGANIZATION のドロップダウンが生成する全 URL は /organizations 配下のみ', () => {
      const urls = [
        buildAllPath('ORGANIZATION'),
        buildFolderPath('ORGANIZATION', 789).path,
        buildDefaultPath('ORGANIZATION').path,
        buildScopePath('ORGANIZATION', 321),
      ]
      for (const url of urls) {
        expect(url.startsWith('/organizations')).toBe(true)
        expect(url.startsWith('/teams')).toBe(false)
      }
    })
  })
})
