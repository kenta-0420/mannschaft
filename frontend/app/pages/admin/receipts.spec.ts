import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'app/pages/admin/receipts.vue'), 'utf8').replace(/\r\n/g, '\n')

/** コメント（説明文に旧名 totalAmount が出てくる）を除いた、実際に動くコード部分。 */
const code = source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '')
  .replace(/<!--[\s\S]*?-->/g, '')

/**
 * 領収書一覧画面の表示・送信の契約（CMP-260907-0915）。
 *
 * 壊れていたこと:
 *  - 一覧の金額列が `data.totalAmount.toLocaleString()` を呼んでいたが BE は `amount` を返すため、
 *    全行で `Cannot read properties of undefined` を投げていた。
 *  - 発行フォームが `totalAmount` を送っていたため、BE の `amount` には何も入らなかった。
 *  - `<DataTable>` に横スクロール指定が無く、390px 幅の実機で見出しが 1 文字ずつ改行されて
 *    高さ 145px の短冊になり、操作列のボタンが viewport 右端を 4px（360px 幅では 34px）超過していた。
 */
describe('/admin/receipts の金額契約とモバイル表示', () => {
  it('一覧の金額列は BE のフィールド名 amount を読む', () => {
    expect(code).toContain('data.amount.toLocaleString')
    expect(code).not.toContain('data.totalAmount')
  })

  it('金額の単位はロケールファイル経由で出す（直書きしない）', () => {
    expect(code).toContain("t('receipt.list.amountWithUnit'")
    expect(code).not.toContain('円</span>')
  })

  it('発行リクエストは amount という名前で金額を送る', () => {
    expect(code).toContain('amount,')
    // コメント中の説明は除いたうえで、実コードに旧名が 1 箇所も残っていないことを見る。
    expect(code).not.toContain('totalAmount')
  })

  it('BE に存在しない notes は送らない（送っても捨てられるため入力欄ごと置かない）', () => {
    expect(code).not.toContain('issueForm.notes')
  })

  it('表は自身のコンテナ内で横スクロールする（ページ本体を横に振らせない）', () => {
    expect(code).toContain('responsive-layout="scroll"')
  })
})
