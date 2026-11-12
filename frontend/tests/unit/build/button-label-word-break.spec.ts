// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * CMP-261112-0518 番人: 共通ボタンラベルの単語途中折れ防止。
 * jsdom はレイアウトを計算できないため main.css の規則を直接読む。
 * `overflow-wrap: anywhere` は min-content 幅の計算にも効き、flex 内のボタンが
 * 1単語より細く縮んで単語途中で折れる（de「Abbrechen」→「Abbreche/n」）。
 * `break-word` は min-content に影響せず、1単語が行に収まらないときだけ折る。
 */
const css = readFileSync(resolve(__dirname, '../../../app/assets/css/main.css'), 'utf-8')

function ruleBody(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp(`${escaped}\\s*\\{([^}]*)\\}`).exec(css)
  expect(match, `${selector} の規則が main.css に存在する`).not.toBeNull()
  return match![1]!
}

describe('共通ボタンラベルの折返し規則', () => {
  const label = () => ruleBody('.p-button .p-button-label')

  it('overflow-wrap: anywhere を使わない（単語途中折れの原因）', () => {
    expect(label()).not.toMatch(/overflow-wrap:\s*anywhere/)
  })

  it('overflow-wrap: break-word で長い単語のはみ出しは防ぐ', () => {
    expect(label()).toMatch(/overflow-wrap:\s*break-word/)
  })

  it('既存のはみ出し防止（min-width:0 と white-space:normal）を維持する', () => {
    expect(label()).toMatch(/min-width:\s*0/)
    expect(label()).toMatch(/white-space:\s*normal/)
  })

  it('ダイアログフッターは flex-wrap で縮み切れないボタンを折り返す', () => {
    expect(ruleBody('.p-dialog-footer')).toMatch(/flex-wrap:\s*wrap/)
  })
})
