import { describe, it, expect } from 'vitest'

/**
 * F11.1 Phase 5: ConflictResolverModal のユニットテスト。
 *
 * コンポーネント内部の差分検出ロジックを抽出してテストする。
 * Vue コンポーネントのマウントは行わず、ロジックのみを検証する。
 */

// ConflictResolverModal.vue から抽出した差分検出ロジック
function getAllKeys(
  client: Record<string, unknown>,
  server: Record<string, unknown>,
): string[] {
  const keys = new Set([...Object.keys(client), ...Object.keys(server)])
  return Array.from(keys).sort()
}

function isDifferent(
  key: string,
  client: Record<string, unknown>,
  server: Record<string, unknown>,
): boolean {
  return JSON.stringify(client[key]) !== JSON.stringify(server[key])
}

function formatValue(value: unknown): string {
  if (value === undefined) return '(undefined)'
  if (value === null) return '(null)'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

describe('ConflictResolverModal ロジック', () => {
  describe('getAllKeys', () => {
    it('クライアントとサーバーの全キーをソート済みで返す', () => {
      const client = { name: 'Alice', age: 30 }
      const server = { name: 'Bob', email: 'bob@example.com' }

      const keys = getAllKeys(client, server)
      expect(keys).toEqual(['age', 'email', 'name'])
    })

    it('両方が空の場合は空配列を返す', () => {
      expect(getAllKeys({}, {})).toEqual([])
    })

    it('重複キーは1つにまとめる', () => {
      const client = { a: 1, b: 2 }
      const server = { b: 3, c: 4 }

      const keys = getAllKeys(client, server)
      expect(keys).toEqual(['a', 'b', 'c'])
    })
  })

  describe('isDifferent', () => {
    it('同じ値の場合は false を返す', () => {
      const data = { status: 'PRESENT' }
      expect(isDifferent('status', data, data)).toBe(false)
    })

    it('異なる値の場合は true を返す', () => {
      const client = { status: 'PRESENT' }
      const server = { status: 'ABSENT' }
      expect(isDifferent('status', client, server)).toBe(true)
    })

    it('片方にしかないキーは差分ありと判定する', () => {
      const client = { name: 'Alice' }
      const server = {}
      expect(isDifferent('name', client, server)).toBe(true)
    })

    it('オブジェクト値の深い比較ができる', () => {
      const client = { data: { nested: { value: 1 } } }
      const server = { data: { nested: { value: 2 } } }
      expect(isDifferent('data', client, server)).toBe(true)
    })

    it('オブジェクト値が同一構造の場合は false を返す', () => {
      const client = { data: { nested: { value: 1 } } }
      const server = { data: { nested: { value: 1 } } }
      expect(isDifferent('data', client, server)).toBe(false)
    })
  })

  describe('formatValue', () => {
    it('undefined を "(undefined)" として返す', () => {
      expect(formatValue(undefined)).toBe('(undefined)')
    })

    it('null を "(null)" として返す', () => {
      expect(formatValue(null)).toBe('(null)')
    })

    it('文字列をそのまま返す', () => {
      expect(formatValue('hello')).toBe('hello')
    })

    it('数値を文字列に変換する', () => {
      expect(formatValue(42)).toBe('42')
    })

    it('オブジェクトを整形 JSON で返す', () => {
      const result = formatValue({ key: 'value' })
      expect(result).toBe('{\n  "key": "value"\n}')
    })

    it('配列を整形 JSON で返す', () => {
      const result = formatValue([1, 2, 3])
      expect(result).toBe('[\n  1,\n  2,\n  3\n]')
    })

    it('boolean を文字列に変換する', () => {
      expect(formatValue(true)).toBe('true')
    })
  })

  describe('解決方法の分岐', () => {
    type Resolution = 'CLIENT_WIN' | 'SERVER_WIN' | 'MANUAL_MERGE'

    function buildResolveBody(
      resolution: Resolution,
      mergedData?: Record<string, unknown>,
    ): Record<string, unknown> {
      const body: Record<string, unknown> = { resolution }
      if (mergedData) {
        body.merged_data = JSON.stringify(mergedData)
      }
      return body
    }

    it('CLIENT_WIN のボディに merged_data が含まれない', () => {
      const body = buildResolveBody('CLIENT_WIN')
      expect(body).toEqual({ resolution: 'CLIENT_WIN' })
      expect(body.merged_data).toBeUndefined()
    })

    it('SERVER_WIN のボディに merged_data が含まれない', () => {
      const body = buildResolveBody('SERVER_WIN')
      expect(body).toEqual({ resolution: 'SERVER_WIN' })
    })

    it('MANUAL_MERGE のボディに merged_data が含まれる', () => {
      const body = buildResolveBody('MANUAL_MERGE', { status: 'MERGED' })
      expect(body.resolution).toBe('MANUAL_MERGE')
      expect(body.merged_data).toBe('{"status":"MERGED"}')
    })
  })
})
