import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useDatetime } from '~/composables/useDatetime'
import { useAuthStore } from '~/stores/useAuthStore'

/**
 * useDatetime().buildOffsetDateTimeStr のユニットテスト（Issue #2508 FEオフセット明示）。
 *
 * 背景:
 *   BE の LocalDateTime 受信フィールドは、送信時（ユーザーTZへ変換して出力）と
 *   受信時（TZを無視して壁時計として保持）が非対称になっている。この非対称を BE 側で
 *   是正する前提として、FE は buildOffsetDateTimeStr でユーザーTZのオフセットを明示的に
 *   付与した ISO 文字列を送る必要がある。
 *
 * 検証観点:
 *   ODT-001: time 省略 → Date 自身の瞬間をユーザーTZのオフセット付きで表現する
 *   ODT-002: 非JST（America/Los_Angeles）でもそのTZの正しいオフセットが付く（+09:00 固定ではない）
 *   ODT-003: time 指定（"HH:mm"）→ 日付+指定時刻をユーザーTZの壁時計として解釈する
 *   ODT-004: time === '' （終日）→ 00:00:00 として解釈する
 *   ODT-005: date が null → null を返す
 *   ODT-006: authStore.user が未設定（未ログイン相当）→ 既定 TZ Asia/Tokyo として扱う
 *   ODT-007: 夏時間（PDT -07:00）と標準時間（PST -08:00）の両方で正しいオフセットが付く
 */

/** 指定タイムゾーンで処理を実行する（Node は process.env.TZ の実行時変更を反映する）。 */
function withSystemTz<T>(tz: string, fn: () => T): T {
  const original = process.env.TZ
  process.env.TZ = tz
  try {
    return fn()
  } finally {
    process.env.TZ = original
  }
}

function setUserTimezone(timezone: string | undefined) {
  const authStore = useAuthStore()
  if (timezone === undefined) {
    authStore.user = null
    return
  }
  authStore.user = {
    id: 1,
    email: 'user@example.com',
    fullName: 'Test User',
    profileImageUrl: null,
    timezone,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('useDatetime().buildOffsetDateTimeStr', () => {
  it('ODT-001: time 省略時は Date 自身の瞬間をユーザーTZのオフセット付きで表現する（JST）', () => {
    withSystemTz('Asia/Tokyo', () => {
      setUserTimezone('Asia/Tokyo')
      const { buildOffsetDateTimeStr } = useDatetime()
      // システムTZ = ユーザーTZ = JST のため、数字は変わらずオフセットだけが付く
      const d = new Date(2026, 5, 5, 9, 30, 0) // 2026-06-05 09:30 JST
      expect(buildOffsetDateTimeStr(d)).toBe('2026-06-05T09:30:00+09:00')
    })
  })

  it('ODT-002: 非JST（America/Los_Angeles）ユーザーでも +09:00 固定ではなく正しいオフセットが付く', () => {
    withSystemTz('America/Los_Angeles', () => {
      setUserTimezone('America/Los_Angeles')
      const { buildOffsetDateTimeStr } = useDatetime()
      // システムTZ = ユーザーTZ = LA のため、数字は変わらずそのTZのオフセットが付く（6月 = PDT -07:00）
      const d = new Date(2026, 5, 5, 9, 30, 0) // 2026-06-05 09:30 PDT
      expect(buildOffsetDateTimeStr(d)).toBe('2026-06-05T09:30:00-07:00')
    })
  })

  it('ODT-007: 冬季（PST -08:00）でも正しいオフセットが付く（DST考慮）', () => {
    withSystemTz('America/Los_Angeles', () => {
      setUserTimezone('America/Los_Angeles')
      const { buildOffsetDateTimeStr } = useDatetime()
      const d = new Date(2026, 0, 15, 9, 30, 0) // 2026-01-15 09:30 PST
      expect(buildOffsetDateTimeStr(d)).toBe('2026-01-15T09:30:00-08:00')
    })
  })

  it('ODT-003: time 指定（"HH:mm"）は日付+指定時刻をユーザーTZの壁時計として解釈する', () => {
    withSystemTz('America/Los_Angeles', () => {
      setUserTimezone('America/Los_Angeles')
      const { buildOffsetDateTimeStr } = useDatetime()
      // 日付のみが意味を持つ Date（datetime-local の日付部分相当）
      const d = new Date(2026, 5, 5) // 2026-06-05 00:00 (システムTZ=ユーザーTZなので日付はそのまま)
      expect(buildOffsetDateTimeStr(d, '14:00')).toBe('2026-06-05T14:00:00-07:00')
    })
  })

  it('ODT-004: time === "" （終日）は 00:00:00 として解釈する', () => {
    withSystemTz('Asia/Tokyo', () => {
      setUserTimezone('Asia/Tokyo')
      const { buildOffsetDateTimeStr } = useDatetime()
      const d = new Date(2026, 5, 5)
      expect(buildOffsetDateTimeStr(d, '')).toBe('2026-06-05T00:00:00+09:00')
    })
  })

  it('ODT-005: date が null の場合は null を返す', () => {
    setUserTimezone('Asia/Tokyo')
    const { buildOffsetDateTimeStr } = useDatetime()
    expect(buildOffsetDateTimeStr(null)).toBeNull()
    expect(buildOffsetDateTimeStr(null, '09:00')).toBeNull()
  })

  it('ODT-006: authStore.user が未設定の場合は既定 TZ Asia/Tokyo として扱う', () => {
    withSystemTz('Asia/Tokyo', () => {
      setUserTimezone(undefined)
      const { buildOffsetDateTimeStr } = useDatetime()
      const d = new Date(2026, 5, 5, 9, 30, 0)
      expect(buildOffsetDateTimeStr(d)).toBe('2026-06-05T09:30:00+09:00')
    })
  })
})
