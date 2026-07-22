import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * issue #2426: useErrorHandler の resolveMessage が BE の理由入り message を
 * 汎用 i18n キーで握りつぶしていた問題の根治テスト（案A: BE message 優先）。
 *
 * テストケース一覧:
 *  EH-001: message が非空なら i18n キーの有無に関わらず message をそのまま返す（AC-1）
 *  EH-002: message が undefined かつ i18n キーが在れば t(key) を返す（AC-2/AC-3 回帰なし）
 *  EH-003: message が空文字かつ i18n キーが在れば t(key) を返す（空文字は「未指定」扱い）
 *  EH-004: message が undefined かつ i18n キーも無ければ t('error.unknown') を返す（AC-3）
 *  EH-005: handleApiError — BE message 付きエラーで notification.error に BE message が渡る（AC-1）
 *  EH-006: handleApiError — BE message 無しのエラーは i18n キー解決文言にフォールバックする（AC-2）
 *  EH-007: handleApiError — ENTITLEMENT_003 は従来どおり通知をスキップする（既存挙動の回帰なし）
 *  EH-008: getFieldErrors — 既存の FIELD_ERROR_PATTERNS マッチングは resolveMessage 変更の影響を受けない
 */

// ============================================================
// useI18n の Nuxt auto-import モック
// error.COMMON_001 のみ翻訳キーが存在する想定にし、
// error.COMMON_999_UNKNOWN_KEY は存在しないキーとして扱う。
// ============================================================
const KNOWN_KEYS = new Set(['error.COMMON_001', 'error.unknown', 'error.server', 'error.server_retry', 'dialog.error', 'invalid_format', 'required'])

const tMock = vi.fn((key: string): string => {
  if (key === 'error.COMMON_001') return 'よくあるエラーです（キー解決）'
  if (key === 'error.unknown') return 'エラーが発生しました'
  if (key === 'error.server') return 'サーバーエラー'
  if (key === 'error.server_retry') return 'しばらくしてから再度お試しください'
  if (key === 'dialog.error') return 'エラー'
  return key
})
const teMock = vi.fn((key: string): boolean => KNOWN_KEYS.has(key))

mockNuxtImport('useI18n', () => () => ({ t: tMock, te: teMock }))

// ============================================================
// useNotification / useErrorReport のモック
// ============================================================
const mockNotifyError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: mockNotifyError,
  }),
}))

const mockCaptureQuiet = vi.fn()
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    captureQuiet: mockCaptureQuiet,
  }),
}))

// テスト対象（モック設定後に動的 import。@nuxt/test-utils の hoisting に依存するため
// import/first の ESLint ルールを無効化する）
// eslint-disable-next-line import/first
import { useErrorHandler } from '~/composables/useErrorHandler'

beforeEach(() => {
  tMock.mockClear()
  teMock.mockClear()
  mockNotifyError.mockReset()
  mockCaptureQuiet.mockReset()
})

describe('useErrorHandler', () => {
  describe('resolveMessage', () => {
    it('EH-001: message が非空なら i18n キーが在っても message を最優先で返す', () => {
      const { resolveMessage } = useErrorHandler()

      const result = resolveMessage('COMMON_001', '画像は30枚までアップロードできます')

      expect(result).toBe('画像は30枚までアップロードできます')
      // BE message を返す場合、i18n キー解決は行われない
      expect(teMock).not.toHaveBeenCalled()
    })

    it('EH-002: message が undefined かつ i18n キーが在れば t(key) にフォールバックする', () => {
      const { resolveMessage } = useErrorHandler()

      const result = resolveMessage('COMMON_001', undefined)

      expect(result).toBe('よくあるエラーです（キー解決）')
    })

    it('EH-003: message が空文字かつ i18n キーが在れば t(key) にフォールバックする', () => {
      const { resolveMessage } = useErrorHandler()

      const result = resolveMessage('COMMON_001', '')

      expect(result).toBe('よくあるエラーです（キー解決）')
    })

    it('EH-004: message が undefined かつ i18n キーも無ければ t(error.unknown) を返す', () => {
      const { resolveMessage } = useErrorHandler()

      const result = resolveMessage('COMMON_999_UNKNOWN_KEY', undefined)

      expect(result).toBe('エラーが発生しました')
    })
  })

  describe('handleApiError', () => {
    it('EH-005: BE message 付きエラーは notification.error に BE message がそのまま渡る', () => {
      const { handleApiError } = useErrorHandler()

      handleApiError({
        data: {
          error: {
            code: 'COMMON_002',
            message: '画像は30枚までアップロードできます',
          },
        },
      })

      expect(mockNotifyError).toHaveBeenCalledWith('エラー', '画像は30枚までアップロードできます')
    })

    it('EH-006: BE message 無しのエラーは i18n キー解決文言にフォールバックする（回帰なし）', () => {
      const { handleApiError } = useErrorHandler()

      handleApiError({
        data: {
          error: {
            code: 'COMMON_001',
          },
        },
      })

      expect(mockNotifyError).toHaveBeenCalledWith('エラー', 'よくあるエラーです（キー解決）')
    })

    it('EH-007: ENTITLEMENT_003 は従来どおり通知をスキップする', () => {
      const { handleApiError } = useErrorHandler()

      handleApiError({
        data: {
          error: {
            code: 'ENTITLEMENT_003',
            message: 'ペイウォール理由の message',
          },
        },
      })

      expect(mockNotifyError).not.toHaveBeenCalled()
    })
  })

  describe('getFieldErrors', () => {
    it('EH-008: FIELD_ERROR_PATTERNS マッチングは resolveMessage 変更の影響を受けない', () => {
      const { getFieldErrors } = useErrorHandler()

      const fieldErrors = getFieldErrors({
        data: {
          error: {
            fieldErrors: [{ field: 'email', message: 'must not be blank' }],
          },
        },
      })

      expect(fieldErrors.email).toBe('required')
    })
  })
})
