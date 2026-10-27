import { describe, expect, it } from 'vitest'
import { inviteRedirect } from './inviteRedirect'

describe('招待の内部復帰先', () => {
  it('元の招待URLのクエリとhashを保つ', () => {
    expect(inviteRedirect('/invite/abc-123?folder=7#join')).toBe('/invite/abc-123?folder=7#join')
  })
  it.each(
    [
      undefined,
      null,
      [],
      [' /invite/abc'],
      'https://example.com',
      '//example.com',
      '/dashboard',
      '/invite/',
      '/invite/abc/../../login',
      '/invite/abc\\evil',
      '/invite/abc' + String.fromCharCode(10),
    ].map((value) => [value]),
  )('外部や不正な復帰先%jを受け入れない', (value) => {
    expect(inviteRedirect(value)).toBeUndefined()
  })
})
