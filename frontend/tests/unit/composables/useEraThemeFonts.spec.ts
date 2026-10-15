import { describe, it, expect, beforeEach } from 'vitest'
import { useEraThemeFonts } from '~/composables/useEraThemeFonts'

describe('F12.6: useEraThemeFonts', () => {
  beforeEach(() => {
    // 各テストの前にheadをリセット
    document.head.innerHTML = ''
  })

  it('fcテーマはPress Start 2Pフォントのlinkを注入する', () => {
    const { loadFont } = useEraThemeFonts()
    loadFont('fc')
    const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'))
    expect(links.some((l) => l.getAttribute('href')?.includes('Press+Start+2P'))).toBe(true)
  })

  it('sfcテーマはDotGothic16フォントのlinkを注入する', () => {
    const { loadFont } = useEraThemeFonts()
    loadFont('sfc')
    const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'))
    expect(links.some((l) => l.getAttribute('href')?.includes('DotGothic16'))).toBe(true)
  })

  it('ホワイトリスト外のテーマではlink要素を注入しない', () => {
    const { loadFont } = useEraThemeFonts()
    const before = document.querySelectorAll('link[rel="stylesheet"]').length
    loadFont('modern')
    loadFont('y1998')
    loadFont('y2000')
    expect(document.querySelectorAll('link[rel="stylesheet"]').length).toBe(before)
  })

  it('同じフォントを2回呼んでも重複注入しない', () => {
    const { loadFont } = useEraThemeFonts()
    loadFont('fc')
    loadFont('fc')
    const links = document.querySelectorAll('link[href*="Press+Start+2P"]')
    expect(links.length).toBe(1)
  })

  it('注入したlinkにreferrerpolicy="no-referrer"が設定される', () => {
    const { loadFont } = useEraThemeFonts()
    loadFont('sfc')
    const link = document.querySelector('link[rel="stylesheet"]')
    expect(link?.getAttribute('referrerpolicy')).toBe('no-referrer')
  })
})
