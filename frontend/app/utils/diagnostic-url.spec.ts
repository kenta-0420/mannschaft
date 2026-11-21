// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { safeDiagnosticUrl } from './diagnostic-url'

describe('診断URLの反復適用と境界', () => {
  it.each([
    'https://app.test/reset-password',
    'https://app.test/',
    'http://app.test:8081/p',
    'https://localhost/p',
    'https://[::1]:8081/p',
    '/a%3Fpart%23anchor',
    '/normal/42/%E6%97%A5',
    '/invalid-url',
  ])('正常pathは反復適用しても変えない %s', (input) => {
    expect(safeDiagnosticUrl(input)).toBe(input)
    expect(safeDiagnosticUrl(safeDiagnosticUrl(input))).toBe(input)
  })

  it.each([
    null,
    undefined,
    '',
    ' ',
    '/p\u0000?token=SYNTHETIC',
    '/p\u0085?token=SYNTHETIC',
    '/\ufeffp?token=SYNTHETIC',
    'https://app.test:99999/p',
    'https://bad_host.test/p',
    'https://127.1/p',
  ])('null・空・制御文字・異常portに生値fallbackしない %j', (input) => {
    expect(safeDiagnosticUrl(input)).toBe('/invalid-url')
    expect(safeDiagnosticUrl(safeDiagnosticUrl(input))).toBe('/invalid-url')
  })

  it('path内のencoded delimiterをquery/fragmentにdecodeしない', () => {
    expect(
      safeDiagnosticUrl(
        'https://user:PASS@app.test/a%3Ftoken%3DSYNTHETIC%23x?token=QUERY#FRAGMENT',
      ),
    ).toBe('https://app.test/a%3Ftoken%3DSYNTHETIC%23x')
  })
})
