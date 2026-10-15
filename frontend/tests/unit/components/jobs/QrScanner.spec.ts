import { describe, it, expect } from 'vitest'

/**
 * F13.1 Phase 13.1.2 {@code QrScanner.vue} のユニットテスト。
 *
 * <p>{@code QrCheckInDisplay.spec.ts} と同方針で、コンポーネント内部の
 * 純関数ロジック（shortCode バリデーション・geo 集約）を抽出してテストする。
 * カメラ起動 / 権限ダイアログ表示などの挙動は E2E（JOB-010〜）でカバーする。</p>
 */

/** 手動入力された shortCode のバリデーション。大文字小文字英数字 6 桁のみ許可。 */
function isValidShortCode(input: string): boolean {
  return /^[A-Za-z0-9]{6}$/.test(input.trim())
}

/** 入力を正規化（trim + 大文字化）。 */
function normalizeShortCode(input: string): string {
  return input.trim().toUpperCase()
}

/** 位置情報同意＋ snapshot から送信用 geo 情報を合成する。 */
function composeGeoForPayload(
  consent: boolean,
  snap: { latitude: number; longitude: number; accuracy: number | null } | null,
): { lat: number | null; lng: number | null; accuracy: number | null } {
  if (!consent || !snap) return { lat: null, lng: null, accuracy: null }
  return { lat: snap.latitude, lng: snap.longitude, accuracy: snap.accuracy }
}

describe('QrScanner ロジック', () => {
  describe('isValidShortCode', () => {
    it('6 桁の英数字（大文字）は通る', () => {
      expect(isValidShortCode('ABC123')).toBe(true)
    })

    it('6 桁の英数字（小文字）も通る（コンポーネント側で大文字化される）', () => {
      expect(isValidShortCode('abc123')).toBe(true)
    })

    it('5 桁以下は不正', () => {
      expect(isValidShortCode('AB123')).toBe(false)
    })

    it('7 桁以上は不正', () => {
      expect(isValidShortCode('ABC1234')).toBe(false)
    })

    it('記号を含むと不正', () => {
      expect(isValidShortCode('ABC-12')).toBe(false)
    })

    it('前後の空白は trim される', () => {
      expect(isValidShortCode('  ABC123  ')).toBe(true)
    })

    it('空文字は不正', () => {
      expect(isValidShortCode('')).toBe(false)
    })
  })

  describe('normalizeShortCode', () => {
    it('小文字を大文字に変換し、前後空白を削る', () => {
      expect(normalizeShortCode('  abc123 ')).toBe('ABC123')
    })
  })

  describe('composeGeoForPayload', () => {
    it('consent=false ならすべて null', () => {
      expect(
        composeGeoForPayload(false, { latitude: 1, longitude: 2, accuracy: 3 }),
      ).toEqual({ lat: null, lng: null, accuracy: null })
    })

    it('consent=true でも snap=null なら null', () => {
      expect(composeGeoForPayload(true, null)).toEqual({
        lat: null,
        lng: null,
        accuracy: null,
      })
    })

    it('consent=true かつ snap あり → そのまま展開', () => {
      expect(
        composeGeoForPayload(true, { latitude: 35.6, longitude: 139.7, accuracy: 10 }),
      ).toEqual({ lat: 35.6, lng: 139.7, accuracy: 10 })
    })

    it('accuracy が null でも展開する', () => {
      expect(
        composeGeoForPayload(true, { latitude: 35.6, longitude: 139.7, accuracy: null }),
      ).toEqual({ lat: 35.6, lng: 139.7, accuracy: null })
    })
  })
})
