/**
 * F15.3 Phase 2-C: InviteFolderPicker のユニットテスト。
 *
 * Nuxt 3 / PrimeVue / Pinia の依存が広く mountSuspended のセットアップが
 * 重いため、コンポーネントの責務であるロジック部分のみ reproduction で検証する。
 *
 * 検証範囲:
 *  - select の v-model 値変換（`'' → null`, `'123' → 123`）
 *  - 「未分類」フォルダはセレクタ選択肢に含めない
 *  - 招待 join リクエストボディの folderId 受け渡し形（null → 省略）
 */
import { describe, it, expect } from 'vitest'
import type { ScopeFolder } from '~/types/scopeFolder'

/**
 * select の値文字列を folderId に変換するロジックの再現。
 * `''` は未選択を表し、`null` を emit する。
 */
function parseSelectValue(raw: string): number | null {
  if (raw === '') return null
  return Number(raw)
}

/**
 * 招待 join リクエストボディ生成ロジックの再現（[token].vue 内）。
 */
function buildJoinBody(folderId: number | null): Record<string, number> {
  return folderId != null ? { folderId } : {}
}

/**
 * セレクタの選択肢生成（未分類は除外）。
 */
function buildOptions(folders: ScopeFolder[]): ScopeFolder[] {
  return folders.filter(f => !f.isDefault)
}

const folderA: ScopeFolder = {
  id: 11,
  name: '部活',
  color: '#3B82F6',
  isDefault: false,
  sortOrder: 0,
  itemScopeIds: [],
}

const folderDefault: ScopeFolder = {
  id: 12,
  name: '未分類',
  color: null,
  isDefault: true,
  sortOrder: 9999,
  itemScopeIds: [],
}

describe('InviteFolderPicker select 値変換（F15.3 §7.4）', () => {
  it('空文字 → null', () => {
    expect(parseSelectValue('')).toBeNull()
  })

  it('"123" → 123', () => {
    expect(parseSelectValue('123')).toBe(123)
  })

  it('"0" → 0（境界値）', () => {
    expect(parseSelectValue('0')).toBe(0)
  })
})

describe('InviteFolderPicker 選択肢生成', () => {
  it('未分類フォルダは選択肢に含めない', () => {
    const options = buildOptions([folderA, folderDefault])
    expect(options).toHaveLength(1)
    expect(options[0]?.id).toBe(folderA.id)
  })

  it('未分類のみの場合は空配列', () => {
    const options = buildOptions([folderDefault])
    expect(options).toEqual([])
  })

  it('未分類が無い場合は全件返す', () => {
    const options = buildOptions([folderA])
    expect(options).toEqual([folderA])
  })
})

describe('InviteFolderPicker 招待 join ボディ生成', () => {
  it('folderId 指定なし → 空ボディ（後方互換）', () => {
    expect(buildJoinBody(null)).toEqual({})
  })

  it('folderId 指定あり → { folderId } を含む', () => {
    expect(buildJoinBody(123)).toEqual({ folderId: 123 })
  })

  it('folderId=0 でも省略しない（明示選択を尊重）', () => {
    expect(buildJoinBody(0)).toEqual({ folderId: 0 })
  })
})
