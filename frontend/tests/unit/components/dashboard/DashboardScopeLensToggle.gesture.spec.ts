/**
 * F10.1.1 L1 管理者レンズトグル — ジェスチャ競合排他＋touch/click 二重発火の単体テスト（検分🔴）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §1.3
 *
 * mountSuspended は Nuxt のプラグイン（pinia store / useRoleAccess の auto-import / IntersectionObserver）
 * 依存が広く設定が複雑化するため、ScopeNavDropdown.spec.ts に倣い、コンポーネントの責務である
 * **touch / mouse 経路の二重化と ghost click 無視のロジックをリプロダクション**して検証する。
 *
 * 検証の主眼（DashboardScopeLensToggle.vue の onTouchStart/Move/End・onClick と同一ロジック）:
 *  - タップ（移動量が閾値未満）: toggle が 1 回だけ発火し、後続 ghost click は無視される
 *  - スワイプ（横 8px 超 かつ |Δx| > |Δy|*1.5）: toggle は 0 回（不発火）、ghost click も無視される
 *  - マウス click（touch を介さない）: toggle が 1 回発火する（キーボード Enter/Space も同経路）
 *  - スワイプ時は必ず preventDefault され、後続の ghost click が toggle を切替えない
 */
import { describe, it, expect, vi } from 'vitest'

/**
 * DashboardScopeLensToggle.vue のジェスチャ判定ロジックの再現。
 * 実コンポーネントの onTouchStart / onTouchMove / onTouchEnd / onClick と同一のフラグ遷移を持つ。
 */
function createGestureHarness(toggle: () => void) {
  let touchStartX = 0
  let touchStartY = 0
  let touchMoved = false
  let usedTouch = false

  function onTouchStart(x: number, y: number) {
    touchStartX = x
    touchStartY = y
    touchMoved = false
  }

  function onTouchMove(x: number, y: number) {
    const dx = Math.abs(x - touchStartX)
    const dy = Math.abs(y - touchStartY)
    // F22.1 カルーセルの横ロック条件（横 8px 超 かつ |Δx| > |Δy|*1.5）に整合。
    if (dx > 8 && dx > dy * 1.5) {
      touchMoved = true
    }
  }

  /** @returns preventDefault が呼ばれたか（= 後続 ghost click が殺されるか） */
  function onTouchEnd(): { preventedDefault: boolean } {
    usedTouch = true
    // スワイプ・タップいずれも、まず後続の ghost click を確実に殺す。
    const preventedDefault = true
    if (touchMoved) {
      touchMoved = false
      return { preventedDefault }
    }
    toggle()
    return { preventedDefault }
  }

  function onClick() {
    if (usedTouch) {
      usedTouch = false
      return
    }
    toggle()
  }

  return { onTouchStart, onTouchMove, onTouchEnd, onClick }
}

describe('DashboardScopeLensToggle ジェスチャ排他（§1.3）', () => {
  it('タップ（移動量ゼロ）: toggle が 1 回だけ発火し、後続 ghost click は無視される', () => {
    const toggle = vi.fn()
    const h = createGestureHarness(toggle)

    h.onTouchStart(100, 200)
    // 移動なし（タップ）
    const end = h.onTouchEnd()
    expect(end.preventedDefault).toBe(true) // ghost click を殺す
    expect(toggle).toHaveBeenCalledTimes(1)

    // ブラウザが発火させる ghost click
    h.onClick()
    // ghost click では toggle が増えない（合計 1 回のまま）
    expect(toggle).toHaveBeenCalledTimes(1)
  })

  it('スワイプ（横 20px 移動・縦わずか）: toggle は 0 回・preventDefault・ghost click も無視', () => {
    const toggle = vi.fn()
    const h = createGestureHarness(toggle)

    h.onTouchStart(100, 200)
    h.onTouchMove(120, 202) // dx=20>8, dx>dy*1.5 → スワイプ判定
    const end = h.onTouchEnd()
    expect(end.preventedDefault).toBe(true)
    // スワイプ時は toggle 不発火（「スワイプしたつもりでトグルが切替わる」事故の根絶）
    expect(toggle).not.toHaveBeenCalled()

    // 後続 ghost click も無視される
    h.onClick()
    expect(toggle).not.toHaveBeenCalled()
  })

  it('縦スクロール（縦 30px・横わずか）はスワイプ扱いせずタップとして toggle 1 回', () => {
    // |Δx|(2) は 8px 超を満たさないため touchMoved=false（カルーセル横送りではない）。
    const toggle = vi.fn()
    const h = createGestureHarness(toggle)

    h.onTouchStart(100, 200)
    h.onTouchMove(102, 230) // dx=2 ≤ 8 → スワイプ判定にならない
    h.onTouchEnd()
    expect(toggle).toHaveBeenCalledTimes(1)
  })

  it('横移動が閾値ぎりぎり（dx=8）はスワイプ判定にならずタップ扱い', () => {
    const toggle = vi.fn()
    const h = createGestureHarness(toggle)

    h.onTouchStart(100, 200)
    h.onTouchMove(108, 200) // dx=8（>8 ではない）→ touchMoved=false
    h.onTouchEnd()
    expect(toggle).toHaveBeenCalledTimes(1)
  })

  it('マウス click（touch を介さない）: toggle が 1 回発火する', () => {
    const toggle = vi.fn()
    const h = createGestureHarness(toggle)

    // touch イベントを介さない純粋な click（マウス / キーボード Enter・Space）
    h.onClick()
    expect(toggle).toHaveBeenCalledTimes(1)
  })

  it('スワイプ後にマウスで click すると toggle が発火する（usedTouch が次の操作に持ち越されない）', () => {
    const toggle = vi.fn()
    const h = createGestureHarness(toggle)

    // 1 回目: スワイプ → toggle 0・usedTouch=true
    h.onTouchStart(100, 200)
    h.onTouchMove(120, 202)
    h.onTouchEnd()
    h.onClick() // ghost click 無視・usedTouch を下ろす
    expect(toggle).not.toHaveBeenCalled()

    // 2 回目: 純マウス click → toggle 1 回
    h.onClick()
    expect(toggle).toHaveBeenCalledTimes(1)
  })
})
