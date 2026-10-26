import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'

/**
 * F18 Phase 4 — BarcodePreview.vue のユニットテスト。
 *
 * <p>3 系統のレンダラー (qrcode / bwip-js / jsbarcode) と NONE フォールバック、
 * および不正値時の renderError 表示の 5 分岐を検証する。</p>
 *
 * <p>モック方針:
 *   - jsbarcode はデフォルトエクスポートを vi.fn() に差し替え
 *   - qrcode は toCanvas を vi.fn() に差し替え
 *   - bwip-js は toCanvas を vi.fn() に差し替え（PDF417 経路で動的 import される）</p>
 *
 * <p>テストケース一覧:
 *   BPR-001: format=QR で共有コンポーネント QrCodeImage が描画され QRCode.toCanvas は呼ばれない
 *   BPR-002: format=PDF417 で bwip-js.toCanvas が呼ばれる
 *   BPR-003: format=CODE128 で JsBarcode が呼ばれる
 *   BPR-004: format=NONE で描画系は一切呼ばれず no_barcode 表示が出る
 *   BPR-005: jsbarcode が例外を投げた場合 renderError 表示が出る</p>
 */

// === モジュールモック ===
// vi.mock は hoist されるため、参照する mock 関数も vi.hoisted で作成する必要がある。

const { mockJsBarcode, mockQrToCanvas, mockQrToString, mockBwipToCanvas } = vi.hoisted(() => ({
  mockJsBarcode: vi.fn(),
  mockQrToCanvas: vi.fn().mockResolvedValue(undefined),
  // 共有コンポーネント QrCodeImage は QRCode.toString(type:'svg') を使う。
  mockQrToString: vi.fn().mockResolvedValue('<svg data-testid="qr-svg"></svg>'),
  mockBwipToCanvas: vi.fn(),
}))

vi.mock('jsbarcode', () => ({
  default: mockJsBarcode,
}))

vi.mock('qrcode', () => ({
  default: {
    toCanvas: mockQrToCanvas,
    toString: mockQrToString,
  },
}))

// 実装側は `bwip-js/browser` サブパスで動的 import するため、こちらをモックする。
vi.mock('bwip-js/browser', () => ({
  toCanvas: mockBwipToCanvas,
  default: { toCanvas: mockBwipToCanvas },
}))

// === テスト本体 ===

describe('BarcodePreview.vue', () => {
  beforeEach(() => {
    mockJsBarcode.mockReset()
    mockQrToCanvas.mockReset()
    mockQrToCanvas.mockResolvedValue(undefined)
    mockQrToString.mockReset()
    mockQrToString.mockResolvedValue('<svg data-testid="qr-svg"></svg>')
    mockBwipToCanvas.mockReset()
  })

  it('BPR-001: format=QR で共有コンポーネント QrCodeImage が描画され QRCode.toCanvas は呼ばれない', async () => {
    const wrapper = await mountSuspended(BarcodePreview, {
      props: { value: 'https://example.com/abc', format: 'QR' },
    })
    // QrCodeImage の onMounted → QRCode.toString(svg) 描画を待つ
    await new Promise(r => setTimeout(r, 10))
    // QR は canvas ではなく共有コンポーネント QrCodeImage(SVG) で描画される
    expect(wrapper.html()).toContain('qr-code-image')
    // 従来の canvas 版 QR 生成（toCanvas）は使わない
    expect(mockQrToCanvas).not.toHaveBeenCalled()
    // 共有コンポーネントは SVG 生成（toString）を props.value で呼ぶ
    expect(mockQrToString).toHaveBeenCalled()
    const [value] = mockQrToString.mock.calls[0]!
    expect(value).toBe('https://example.com/abc')
    expect(mockJsBarcode).not.toHaveBeenCalled()
    expect(mockBwipToCanvas).not.toHaveBeenCalled()
  })

  it('BPR-002: format=PDF417 で bwip-js.toCanvas が呼ばれる', async () => {
    await mountSuspended(BarcodePreview, {
      props: { value: '1234567890123456', format: 'PDF417' },
    })
    // 動的 import の解決待ち
    await new Promise(r => setTimeout(r, 30))
    expect(mockBwipToCanvas).toHaveBeenCalled()
    const callArgs = mockBwipToCanvas.mock.calls[0]!
    const opts = callArgs[1] as Record<string, unknown>
    expect(opts.bcid).toBe('pdf417')
    expect(opts.text).toBe('1234567890123456')
    expect(mockJsBarcode).not.toHaveBeenCalled()
    expect(mockQrToCanvas).not.toHaveBeenCalled()
  })

  it('BPR-003: format=CODE128 で JsBarcode が呼ばれる', async () => {
    await mountSuspended(BarcodePreview, {
      props: { value: '4901234567894', format: 'CODE128' },
    })
    await new Promise(r => setTimeout(r, 10))
    expect(mockJsBarcode).toHaveBeenCalled()
    const callArgs = mockJsBarcode.mock.calls[0]!
    expect(callArgs[1]).toBe('4901234567894')
    const opts = callArgs[2] as Record<string, unknown>
    expect(opts.format).toBe('CODE128')
    expect(mockQrToCanvas).not.toHaveBeenCalled()
    expect(mockBwipToCanvas).not.toHaveBeenCalled()
  })

  it('BPR-004: format=NONE で描画系は一切呼ばれず no_barcode 表示が出る', async () => {
    const wrapper = await mountSuspended(BarcodePreview, {
      props: { value: '1234567890', format: 'NONE' },
    })
    await new Promise(r => setTimeout(r, 10))
    expect(mockJsBarcode).not.toHaveBeenCalled()
    expect(mockQrToCanvas).not.toHaveBeenCalled()
    expect(mockBwipToCanvas).not.toHaveBeenCalled()
    // no_barcode_label / value-text 表示の存在を緩く検証
    const html = wrapper.html()
    expect(html).toContain('barcode-preview__no-barcode')
  })

  it('BPR-005: jsbarcode が例外を投げた場合 renderError 表示が出る', async () => {
    mockJsBarcode.mockImplementation(() => {
      throw new Error('invalid value')
    })
    const wrapper = await mountSuspended(BarcodePreview, {
      props: { value: 'INVALID', format: 'EAN13' },
    })
    await new Promise(r => setTimeout(r, 20))
    const html = wrapper.html()
    expect(html).toContain('barcode-preview__error')
  })
})
