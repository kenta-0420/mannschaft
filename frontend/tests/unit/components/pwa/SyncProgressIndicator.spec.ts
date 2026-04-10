import { describe, it, expect } from 'vitest'

/**
 * F11.1 SyncProgressIndicator のユニットテスト。
 *
 * コンポーネントの表示条件ロジックをユニットテストする。
 * useSyncStore の状態に応じた表示/非表示の分岐を検証。
 */

interface SyncStoreState {
  syncInProgress: boolean
  pendingCount: number
}

function shouldShow(state: SyncStoreState): 'spinner' | 'badge' | 'hidden' {
  if (state.syncInProgress) return 'spinner'
  if (state.pendingCount > 0) return 'badge'
  return 'hidden'
}

describe('SyncProgressIndicator ロジック', () => {
  it('未送信が0件で同期していないとき何も表示されない', () => {
    const result = shouldShow({ syncInProgress: false, pendingCount: 0 })
    expect(result).toBe('hidden')
  })

  it('未送信がある場合にバッジが表示される', () => {
    const result = shouldShow({ syncInProgress: false, pendingCount: 3 })
    expect(result).toBe('badge')
  })

  it('同期中にスピナーが表示される', () => {
    const result = shouldShow({ syncInProgress: true, pendingCount: 0 })
    expect(result).toBe('spinner')
  })

  it('同期中は未送信バッジよりスピナーが優先される', () => {
    const result = shouldShow({ syncInProgress: true, pendingCount: 5 })
    expect(result).toBe('spinner')
  })
})
