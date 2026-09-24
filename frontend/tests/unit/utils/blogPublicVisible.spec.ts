import { describe, expect, it } from 'vitest'
import { resolveBlogPublicVisible } from '~/utils/blogPublicVisible'

describe('resolveBlogPublicVisible', () => {
  it('取得したfalseを編集画面の初期値として維持する', () => {
    expect(resolveBlogPublicVisible({ publicVisible: false })).toBe(false)
  })

  it('旧レスポンスで項目が無い場合は公開を既定値にする', () => {
    expect(resolveBlogPublicVisible(undefined)).toBe(true)
  })
})
