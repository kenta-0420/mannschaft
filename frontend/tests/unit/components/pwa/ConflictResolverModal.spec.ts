import { describe, it, expect, vi } from 'vitest'

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

  /**
   * confirmDiscard は PrimeVue の useConfirm に require 呼び出しを委譲する。
   * ここでは require に渡す options オブジェクトの構築と、
   * accept/reject コールバックによる handleDiscard の発火挙動を検証する。
   *
   * ConflictResolverModal.vue の confirmDiscard と同じ形を再現している。
   */
  describe('破棄確認ダイアログ (confirmDiscard)', () => {
    interface ConfirmRequireOptions {
      message: string
      header: string
      icon: string
      acceptClass: string
      acceptLabel: string
      rejectLabel: string
      accept: () => void
      reject?: () => void
    }

    interface ConfirmMock {
      require: (options: ConfirmRequireOptions) => void
    }

    function buildConfirmDiscard(
      confirm: ConfirmMock,
      handleDiscard: () => void,
      t: (key: string) => string,
      detail: { id: number } | null,
    ) {
      return () => {
        if (!detail) return
        confirm.require({
          message: t('conflict.discard_confirm'),
          header: t('conflict.discard'),
          icon: 'pi pi-exclamation-triangle',
          acceptClass: 'p-button-danger',
          acceptLabel: t('button.confirm'),
          rejectLabel: t('button.cancel'),
          accept: () => {
            handleDiscard()
          },
        })
      }
    }

    it('detail が null の場合は confirm.require を呼ばない', () => {
      const requireSpy = vi.fn()
      const handleDiscard = vi.fn()
      const t = (key: string) => key
      const confirmDiscard = buildConfirmDiscard(
        { require: requireSpy },
        handleDiscard,
        t,
        null,
      )

      confirmDiscard()

      expect(requireSpy).not.toHaveBeenCalled()
      expect(handleDiscard).not.toHaveBeenCalled()
    })

    it('require に渡す options が i18n キーとアイコンを正しく含む', () => {
      const requireSpy = vi.fn()
      const handleDiscard = vi.fn()
      const t = (key: string) => `translated:${key}`
      const confirmDiscard = buildConfirmDiscard(
        { require: requireSpy },
        handleDiscard,
        t,
        { id: 42 },
      )

      confirmDiscard()

      expect(requireSpy).toHaveBeenCalledTimes(1)
      const options = requireSpy.mock.calls[0]?.[0] as ConfirmRequireOptions
      expect(options.message).toBe('translated:conflict.discard_confirm')
      expect(options.header).toBe('translated:conflict.discard')
      expect(options.icon).toBe('pi pi-exclamation-triangle')
      expect(options.acceptClass).toBe('p-button-danger')
      expect(options.acceptLabel).toBe('translated:button.confirm')
      expect(options.rejectLabel).toBe('translated:button.cancel')
      expect(typeof options.accept).toBe('function')
    })

    it('accept コールバック実行時に handleDiscard が呼ばれる', () => {
      const handleDiscard = vi.fn()
      // require はコールバックを即座に実行せず、保持する
      let captured: ConfirmRequireOptions | null = null
      const confirm: ConfirmMock = {
        require: (options) => {
          captured = options
        },
      }
      const confirmDiscard = buildConfirmDiscard(
        confirm,
        handleDiscard,
        (key) => key,
        { id: 1 },
      )

      confirmDiscard()
      expect(handleDiscard).not.toHaveBeenCalled()

      // ユーザーが「確認」を押したと仮定して accept を発火
      captured!.accept()
      expect(handleDiscard).toHaveBeenCalledTimes(1)
    })

    it('reject 相当の操作では handleDiscard が呼ばれない', () => {
      const handleDiscard = vi.fn()
      let captured: ConfirmRequireOptions | null = null
      const confirm: ConfirmMock = {
        require: (options) => {
          captured = options
        },
      }
      const confirmDiscard = buildConfirmDiscard(
        confirm,
        handleDiscard,
        (key) => key,
        { id: 1 },
      )

      confirmDiscard()
      // accept を呼ばず、ダイアログを閉じたシナリオ
      expect(captured).not.toBeNull()
      expect(handleDiscard).not.toHaveBeenCalled()
    })
  })
})
