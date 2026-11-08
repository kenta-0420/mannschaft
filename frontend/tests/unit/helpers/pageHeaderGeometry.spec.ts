// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { headerGroupsAreOneRow, rectFits, type HeaderRect } from './pageHeaderGeometry'
const rect = (top: number, bottom: number): HeaderRect => ({ top, bottom, left: 0, right: 100, width: 100, height: bottom - top })
describe('CMP-261014-2035 P6/P7矩形判定', () => {
  it('items-endで高さの違う見出しとTagでも縦区間が重なれば一行', () => {
    expect(headerGroupsAreOneRow([rect(0, 44), rect(20, 44)], [rect(0, 44), rect(10, 44)])).toBe(true)
  })
  it('P7 操作群だけが狭幅で折り返した検体を不合格にする', () => {
    expect(headerGroupsAreOneRow([rect(0, 90), rect(0, 90)], [rect(0, 44), rect(46, 90)])).toBe(false)
  })
  it('P7 タイトル行が折り返した検体を不合格にする', () => {
    expect(headerGroupsAreOneRow([rect(0, 44), rect(46, 90)], [rect(0, 44)])).toBe(false)
  })
  it('P6 親とviewport両方の内側を要求する', () => {
    const parent = { ...rect(0, 100), right: 400, width: 400 }
    expect(rectFits({ ...rect(0, 44), left: 350, right: 394, width: 44 }, parent, 360)).toBe(false)
    expect(rectFits({ ...rect(0, 44), left: 10, right: 54, width: 44 }, parent, 360)).toBe(true)
  })
})
