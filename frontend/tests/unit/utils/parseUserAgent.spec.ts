import { describe, it, expect } from 'vitest'
import { parseUserAgent } from '~/utils/parseUserAgent'

/**
 * parseUserAgent ユーティリティのユニットテスト。
 *
 * アクティブセッション一覧のデバイス表示を読みやすくするため、
 * 生 User-Agent から OS / ブラウザ名を抽出する純関数の判定仕様を検証する。
 *
 * 判定の順序（Edge→Opera→Chrome→Firefox→Safari / iOS→Android→Windows→macOS→Linux）が
 * 崩れると誤判定するため、含意関係のある UA（Chrome を含む Edge 等）を重点的にカバーする。
 */
describe('parseUserAgent', () => {
  describe('OS 判定', () => {
    it('Windows NT を Windows と判定する', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ).os,
      ).toBe('Windows')
    })

    it('Mac OS X（iPhone/iPad を含まない）を macOS と判定する', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
        ).os,
      ).toBe('macOS')
    })

    it('iPhone を iOS と判定する（Mac OS X を含んでいても iOS 優先）', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
        ).os,
      ).toBe('iOS')
    })

    it('iPad を iOS と判定する', () => {
      expect(parseUserAgent('Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)').os).toBe('iOS')
    })

    it('Android を Android と判定する（Linux を含んでいても Android 優先）', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
        ).os,
      ).toBe('Android')
    })

    it('Linux（Android でない）を Linux と判定する', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ).os,
      ).toBe('Linux')
    })

    it('判定不能な OS は空文字を返す', () => {
      expect(parseUserAgent('SomeUnknownAgent/1.0').os).toBe('')
    })
  })

  describe('ブラウザ判定', () => {
    it('Edg を Edge と判定する（Chrome を含んでいても Edge 優先）', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0',
        ).browser,
      ).toBe('Edge')
    })

    it('OPR を Opera と判定する（Chrome を含んでいても Opera 優先）', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 OPR/106.0.0.0',
        ).browser,
      ).toBe('Opera')
    })

    it('Chrome を Chrome と判定する（Edg/OPR を含まない）', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ).browser,
      ).toBe('Chrome')
    })

    it('Firefox を Firefox と判定する', () => {
      expect(
        parseUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0')
          .browser,
      ).toBe('Firefox')
    })

    it('Safari を Safari と判定する（Chrome を含まない）', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
        ).browser,
      ).toBe('Safari')
    })

    it('判定不能なブラウザは空文字を返す', () => {
      expect(parseUserAgent('SomeUnknownAgent/1.0').browser).toBe('')
    })
  })

  describe('null / undefined / 空文字', () => {
    it('null は browser/os ともに空文字', () => {
      expect(parseUserAgent(null)).toEqual({ browser: '', os: '' })
    })

    it('undefined は browser/os ともに空文字', () => {
      expect(parseUserAgent(undefined)).toEqual({ browser: '', os: '' })
    })

    it('空文字は browser/os ともに空文字', () => {
      expect(parseUserAgent('')).toEqual({ browser: '', os: '' })
    })
  })

  describe('代表的な実 UA の総合判定', () => {
    it('Windows の Chrome', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ),
      ).toEqual({ browser: 'Chrome', os: 'Windows' })
    })

    it('macOS の Safari', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
        ),
      ).toEqual({ browser: 'Safari', os: 'macOS' })
    })

    it('iOS の Safari', () => {
      expect(
        parseUserAgent(
          'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
        ),
      ).toEqual({ browser: 'Safari', os: 'iOS' })
    })
  })
})
