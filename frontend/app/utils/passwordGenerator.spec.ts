// @vitest-environment node
import { webcrypto } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { generatePassword } from './passwordGenerator'
import { meetsPasswordPolicy } from './passwordPolicy'

afterEach(() => vi.unstubAllGlobals())

describe('本人用パスワード生成 AC-2/3/8', () => {
  it('16文字の英数字と英大小数字3種を繰り返し保証する', () => {
    vi.stubGlobal('crypto', webcrypto)
    for (let i = 0; i < 200; i++) {
      const password = generatePassword()
      expect(password).toMatch(/^[A-Za-z0-9]{16}$/)
      expect(password).toMatch(/[A-Z]/)
      expect(password).toMatch(/[a-z]/)
      expect(password).toMatch(/[0-9]/)
      expect(meetsPasswordPolicy(password)).toBe(true)
    }
  })

  it('各文字種と英数字の棄却境界を捨てて直前値を受理しシャッフルする', () => {
    const bytes = [234, 233, 234, 233, 250, 249, 248, 247]
    const random = vi.fn((array: Uint8Array) => {
      array[0] = bytes.shift() ?? 0
      return array
    })
    vi.stubGlobal('crypto', { getRandomValues: random })
    const password = generatePassword()
    expect(password).toHaveLength(16)
    expect(password).toContain('Z')
    expect(password).toContain('z')
    expect(password).toContain('9')
    expect(random).toHaveBeenCalledTimes(35)
    expect(meetsPasswordPolicy(password)).toBe(true)
  })

  it('Web Crypto非対応では非暗号学的乱数へフォールバックしない', () => {
    vi.stubGlobal('crypto', undefined)
    expect(() => generatePassword()).toThrow()
  })

  it('乱数源の途中失敗を呼出元へ返し不完全な値を返さない', () => {
    const random = vi.fn((array: Uint8Array) => array.fill(0))
      .mockImplementationOnce((array: Uint8Array) => array.fill(0))
      .mockImplementationOnce(() => { throw new Error('unavailable') })
    vi.stubGlobal('crypto', { getRandomValues: random })
    expect(() => generatePassword()).toThrow('unavailable')
  })
})
