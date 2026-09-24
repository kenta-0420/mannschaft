import { describe, expect, it } from 'vitest'
import { PERSONAL_SCOPE_KEY, scheduleScopeKey } from './scheduleScopeKey'

/**
 * F03.19 実機E2E 欠陥2 の再発防止。
 * 選択肢（`TEAM:<slug>`）と作成ダイアログの初期選択（旧 `team_<slug>`）で鍵の形式が
 * 食い違い、初期表示でどのボタンにも選択状態が付かなかった。鍵を作る場所を
 * この関数1つに限ることで、両者が同じ文字列に落ちることを保証する。
 */
describe('scheduleScopeKey', () => {
  it('スコープ種別の大小文字が違っても同じ鍵になる（選択肢側は TEAM・ダイアログ側は team）', () => {
    expect(scheduleScopeKey('TEAM', 'ichigun')).toBe(scheduleScopeKey('team', 'ichigun'))
    expect(scheduleScopeKey('ORGANIZATION', 'yamada-fc')).toBe(scheduleScopeKey('organization', 'yamada-fc'))
  })

  it('区切りはコロンであり、アンダースコア連結ではない', () => {
    // 旧実装（`${scopeType}_${scopeId}`）が復活したらここで落ちる。
    expect(scheduleScopeKey('team', 'ichigun')).toBe('TEAM:ichigun')
    expect(scheduleScopeKey('team', 'ichigun')).not.toContain('_')
  })

  it('別スコープが同じ鍵に潰れない', () => {
    expect(scheduleScopeKey('TEAM', 'a')).not.toBe(scheduleScopeKey('ORGANIZATION', 'a'))
    expect(scheduleScopeKey('TEAM', 'a')).not.toBe(PERSONAL_SCOPE_KEY)
  })
})
