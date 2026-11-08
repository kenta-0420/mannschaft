import {
  BinaryBitmap,
  ChecksumException,
  FormatException,
  HybridBinarizer,
  NotFoundException,
  RGBLuminanceSource,
  QRCodeReader,
} from '@zxing/library'

/** 撮影済み画像のRGB画素を厳密なQR decoderへ渡す。 */
export function decodeQrPixels(
  data: Uint8Array,
  width: number,
  height: number,
  channels: number,
): string {
  let pixels = new Uint8ClampedArray(width * height)
  for (let i = 0; i < pixels.length; i++) {
    pixels[i] = Math.round(
      (data[i * channels]! + data[i * channels + 1]! + data[i * channels + 2]!) / 3,
    )
  }
  let originalError: unknown
  for (let orientation = 0; orientation < 4; orientation++) {
    try {
      return new QRCodeReader()
        .decode(
          new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(pixels, width, height))),
        )
        .getText()
    } catch (error) {
      if (
        !(
          error instanceof NotFoundException ||
          error instanceof FormatException ||
          error instanceof ChecksumException
        )
      )
        throw error
      if (orientation === 0) originalError = error
    }
    if (orientation < 3) {
      // 同じ撮影済み画素を90度ずつ無損失で回す。再撮影・補間・待機はしない。
      const rotated = new Uint8ClampedArray(pixels.length)
      for (let y = 0; y < height; y++)
        for (let x = 0; x < width; x++) {
          rotated[x * height + height - 1 - y] = pixels[y * width + x]!
        }
      pixels = rotated
      ;[width, height] = [height, width]
    }
  }
  throw originalError
}
