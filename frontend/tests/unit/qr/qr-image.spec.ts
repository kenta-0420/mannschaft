// @vitest-environment node
import { readFileSync } from 'node:fs'
import sharp from 'sharp'
import { NotFoundException } from '@zxing/library'
import { describe, expect, it } from 'vitest'
import { decodeQrPixels } from '../../e2e/helpers/qr-image'

// 公開の架空URL。署名・認証情報を含まない固定fixture。
const expected =
  'http://localhost:3004/my/team-points/receive#locationToken=PUBLICZNwYswi-gmOrMMDPCh0eqhE0XXbEi1O9ZX-5N1fTjt4ZNwYswi-gmOrMMDPCh0eqhE0XXbEi1O'
const fixture = readFileSync(new URL('./fixtures/reception-public-mask2.png', import.meta.url))

describe('撮影済みQR画像の復号', () => {
  it.each([0, 90, 180, 270])('%s度の同一QRから厳密に同じ文字列を読む', async (angle) => {
    const { data, info } = await sharp(fixture)
      .rotate(angle)
      .removeAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true })
    expect(decodeQrPixels(data, info.width, info.height, info.channels)).toBe(expected)
  })

  it('白紙はQRとして受け入れない', async () => {
    const { data, info } = await sharp({
      create: { width: 280, height: 280, channels: 3, background: '#ffffff' },
    })
      .raw()
      .toBuffer({ resolveWithObject: true })
    expect(() => decodeQrPixels(data, info.width, info.height, info.channels)).toThrow(
      NotFoundException,
    )
  })
})
