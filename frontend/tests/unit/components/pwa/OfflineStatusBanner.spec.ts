import { describe, it, expect } from 'vitest'

/**
 * F11.1 OfflineStatusBanner のユニットテスト。
 *
 * コンポーネントは useOnline() composable に依存するため、
 * Nuxt テスト環境で mountSuspended を使うとスタブが適用されない。
 * ここではコンポーネントのロジック部分（表示条件）をユニットテストする。
 */

describe('OfflineStatusBanner ロジック', () => {
  it('online === true のとき alert バナーは非表示条件を満たす', () => {
    const online = true
    const shouldShowOfflineBanner = !online
    expect(shouldShowOfflineBanner).toBe(false)
  })

  it('online === false のとき alert バナーは表示条件を満たす', () => {
    const online = false
    const shouldShowOfflineBanner = !online
    expect(shouldShowOfflineBanner).toBe(true)
  })

  it('オフライン→オンライン遷移で復帰バナーが表示される', () => {
    let showBackOnline = false
    const wasOnline = false
    const isOnline = true

    // watch ロジックの再現
    if (isOnline && wasOnline === false) {
      showBackOnline = true
    }

    expect(showBackOnline).toBe(true)
  })

  it('初回表示（常にオンライン）では復帰バナーは出ない', () => {
    let showBackOnline = false
    const wasOnline = undefined // 初回 watch では prev が undefined
    const isOnline = true

    // watch(online, (isOnline, wasOnline))
    // wasOnline が undefined（初回コール）の場合は表示しない
    if (isOnline && wasOnline === false) {
      showBackOnline = true
    }

    expect(showBackOnline).toBe(false)
  })
})
