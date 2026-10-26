import { describe, it, expect } from 'vitest'

/**
 * F13.1 Phase 13.1.2 {@code QrCheckInDisplay.vue} のユニットテスト。
 *
 * <p>コンポーネントは Nuxt Auto-import / PrimeVue / {@code useQrTokenApi} 等に
 * 依存しており、{@code mountSuspended} 環境のセットアップが重い。既存慣例
 * （{@code WidgetMyRecruitments.spec.ts}）に倣い、コンポーネント内の
 * 純関数ロジック（残秒計算・進捗率・severity 判定）を抽出してテストする。</p>
 */

type ProgressSeverity = 'success' | 'warn' | 'danger'

/** コンポーネント内 remainingPercent の再現。 */
function remainingPercent(remainingSeconds: number, ttlSeconds: number): number {
  if (ttlSeconds <= 0) return 0
  return Math.min(100, Math.max(0, (remainingSeconds / ttlSeconds) * 100))
}

/** コンポーネント内 progressSeverity の再現。 */
function progressSeverity(remainingSeconds: number): ProgressSeverity {
  if (remainingSeconds <= 5) return 'danger'
  if (remainingSeconds <= 10) return 'warn'
  return 'success'
}

/** 残秒計算（expiresAt - now を ceil した秒数、0 未満は 0）。 */
function computeRemainingSeconds(nowMs: number, expiresAtMs: number): number {
  return Math.max(0, Math.ceil((expiresAtMs - nowMs) / 1000))
}

/** TTL 推定（expiresAt - issuedAt の秒数、最低 1 秒）。 */
function deriveTtlSeconds(issuedAtIso: string, expiresAtIso: string): number {
  const issuedMs = new Date(issuedAtIso).getTime()
  const expiresMs = new Date(expiresAtIso).getTime()
  return Math.max(1, Math.round((expiresMs - issuedMs) / 1000))
}

describe('QrCheckInDisplay ロジック', () => {
  describe('remainingPercent', () => {
    it('残 60 / TTL 60 → 100%', () => {
      expect(remainingPercent(60, 60)).toBe(100)
    })

    it('残 30 / TTL 60 → 50%', () => {
      expect(remainingPercent(30, 60)).toBe(50)
    })

    it('残 0 / TTL 60 → 0%', () => {
      expect(remainingPercent(0, 60)).toBe(0)
    })

    it('TTL が 0 以下なら 0%（ゼロ除算回避）', () => {
      expect(remainingPercent(10, 0)).toBe(0)
      expect(remainingPercent(10, -1)).toBe(0)
    })

    it('残 > TTL の異常値でも 100% に丸める', () => {
      expect(remainingPercent(120, 60)).toBe(100)
    })

    it('残 < 0 の異常値でも 0% に丸める', () => {
      expect(remainingPercent(-5, 60)).toBe(0)
    })
  })

  describe('progressSeverity', () => {
    it('残 11 秒以上は success', () => {
      expect(progressSeverity(60)).toBe('success')
      expect(progressSeverity(11)).toBe('success')
    })

    it('残 6〜10 秒は warn', () => {
      expect(progressSeverity(10)).toBe('warn')
      expect(progressSeverity(6)).toBe('warn')
    })

    it('残 5 秒以下は danger', () => {
      expect(progressSeverity(5)).toBe('danger')
      expect(progressSeverity(1)).toBe('danger')
      expect(progressSeverity(0)).toBe('danger')
    })
  })

  describe('computeRemainingSeconds', () => {
    it('未来の expiresAt に対して正の残秒を返す', () => {
      const now = 1_700_000_000_000
      expect(computeRemainingSeconds(now, now + 30_000)).toBe(30)
    })

    it('期限切れ（expiresAt <= now）では 0 を返す', () => {
      const now = 1_700_000_000_000
      expect(computeRemainingSeconds(now, now)).toBe(0)
      expect(computeRemainingSeconds(now, now - 1_000)).toBe(0)
    })

    it('小数秒は ceil される（1ms 残なら 1 秒として扱う）', () => {
      const now = 1_700_000_000_000
      expect(computeRemainingSeconds(now, now + 1)).toBe(1)
    })
  })

  describe('deriveTtlSeconds', () => {
    it('30 秒の TTL を正しく算出する', () => {
      const issued = '2026-04-24T12:00:00.000Z'
      const expires = '2026-04-24T12:00:30.000Z'
      expect(deriveTtlSeconds(issued, expires)).toBe(30)
    })

    it('issuedAt と expiresAt が同じでも最低 1 を返す', () => {
      const t = '2026-04-24T12:00:00.000Z'
      expect(deriveTtlSeconds(t, t)).toBe(1)
    })

    it('異常値（expires < issued）でも最低 1 を返す', () => {
      const issued = '2026-04-24T12:00:30.000Z'
      const expires = '2026-04-24T12:00:00.000Z'
      expect(deriveTtlSeconds(issued, expires)).toBe(1)
    })
  })
})
