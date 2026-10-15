import { describe, it, expect } from 'vitest'
import { isEraTheme, ALL_ERA_THEMES, RETRO_ERA_THEMES } from '~/types/eraTheme'
import { isCrawler, isMobile } from '~/composables/useEraTheme'

describe('F12.6: useEraTheme', () => {
  describe('isEraTheme 型ガード', () => {
    it('全有効テーマIDはtrueを返す', () => {
      for (const theme of ALL_ERA_THEMES) {
        expect(isEraTheme(theme)).toBe(true)
      }
    })

    it('無効な文字列はfalseを返す', () => {
      expect(isEraTheme('invalid')).toBe(false)
      expect(isEraTheme('y2099')).toBe(false)
      expect(isEraTheme('')).toBe(false)
    })

    it('プロトタイプ汚染を試みる文字列はfalseを返す', () => {
      expect(isEraTheme('constructor')).toBe(false)
      expect(isEraTheme('__proto__')).toBe(false)
      expect(isEraTheme('hasOwnProperty')).toBe(false)
    })

    it('非文字列型はfalseを返す', () => {
      expect(isEraTheme(null)).toBe(false)
      expect(isEraTheme(undefined)).toBe(false)
      expect(isEraTheme(42)).toBe(false)
      expect(isEraTheme({})).toBe(false)
    })
  })

  describe('isCrawler クローラ判定', () => {
    it('Googlebotを検出する', () => {
      expect(
        isCrawler('Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'),
      ).toBe(true)
    })

    it('Bingbotを検出する', () => {
      expect(isCrawler('Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)')).toBe(
        true,
      )
    })

    it('Chrome-Lighthouseを検出する', () => {
      expect(isCrawler('Mozilla/5.0 (X11; Linux x86_64) Chrome-Lighthouse')).toBe(true)
    })

    it('通常のChromeブラウザはfalseを返す', () => {
      expect(
        isCrawler(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ),
      ).toBe(false)
    })
  })

  describe('isMobile モバイル判定', () => {
    it('Android UAはtrueを返す', () => {
      expect(
        isMobile('Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 Mobile Safari/537.36'),
      ).toBe(true)
    })

    it('iPhone UAはtrueを返す', () => {
      expect(isMobile('Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) Mobile/15E148')).toBe(
        true,
      )
    })

    it('デスクトップChromeはfalseを返す', () => {
      expect(
        isMobile(
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0',
        ),
      ).toBe(false)
    })
  })

  describe('定数の整合性', () => {
    it('ALL_ERA_THEMESにmodernが含まれる', () => {
      expect(ALL_ERA_THEMES).toContain('modern')
    })

    it('RETRO_ERA_THEMESにmodernが含まれない', () => {
      expect(RETRO_ERA_THEMES).not.toContain('modern')
    })

    it('RETRO_ERA_THEMESは8テーマ（fc/sfcを含む）', () => {
      expect(RETRO_ERA_THEMES.length).toBe(8)
      expect(RETRO_ERA_THEMES).toContain('fc')
      expect(RETRO_ERA_THEMES).toContain('sfc')
    })

    it('ALL_ERA_THEMESはRETRO_ERA_THEMES + modern（計9テーマ）', () => {
      expect(ALL_ERA_THEMES.length).toBe(RETRO_ERA_THEMES.length + 1)
    })
  })
})
