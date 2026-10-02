// @vitest-environment node

import { describe, expect, it } from 'vitest'
import { classifyLoadError, getLoadErrorHttpStatus } from '~/utils/loadError'

describe('loadError', () => {
  it.each([
    [{ statusCode: 403 }, 403],
    [{ status: 404 }, 404],
    [{ response: { status: 503 } }, 503],
  ])('HTTP ステータスの表現差を吸収する', (error, expected) => {
    expect(getLoadErrorHttpStatus(error)).toBe(expected)
  })

  it.each([
    [{ statusCode: 403 }, 'forbidden'],
    [{ response: { status: 404 } }, 'notFoundOrForbidden'],
    [{ status: 503 }, 'server'],
    [{ status: 422 }, 'generic'],
    [{ name: 'FetchError', message: 'fetch failed' }, 'network'],
    [new TypeError('Failed to fetch'), 'network'],
    [new Error('unexpected response shape'), 'generic'],
  ] as const)('取得失敗を表示種別へ変換する', (error, expected) => {
    expect(classifyLoadError(error)).toBe(expected)
  })
})
