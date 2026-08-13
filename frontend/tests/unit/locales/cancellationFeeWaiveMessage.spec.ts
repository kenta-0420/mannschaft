import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * F03.11.1 キャンセル料免除 確認ダイアログの文言方針（設計書 §12.1）を固定するテスト。
 *
 * 免除は金銭債権を消す不可逆な操作であり、確認文言の書き方を誤ると運用上の事故
 * （「申込制限が解除される」という誤った期待→問い合わせ）に直結する。文言そのものを
 * ロジックではなくテキストとして固定することで、後から気軽に書き換えられないようにする。
 *
 * <p>ロケールファイル（{@code recruitment.ts}）は Nuxt i18n のビルド変換対象であり、
 * vitest から素で import すると変換後の形（プレーンオブジェクトではない）になり値が
 * 正しく取れない。そのため本テストは <b>ソースファイルをテキストとして読み、正規表現で
 * message フィールドの値を抜き出す</b>——変換パイプラインに依存せず、実際にコミットされた
 * 文言そのものを検証する。</p>
 *
 * 方針（設計書 §12.1）:
 *   1. 「このユーザーの募集への申込制限が解除されます」と言い切る文言を置いてはならない
 *   2. 1 文目に必ず起こること（債権の放棄・不可逆）、2 文目に条件付きであることを書く。順序を入れ替えない
 *   3. 「解除されます」ではなく「残っている場合は解除されません」の否定形で書く
 */
function extractMessage(): string {
  // vitest はプロジェクトルート（frontend/）を cwd として実行される。
  const path = join(process.cwd(), 'app/locales/ja/recruitment.ts')
  const source = readFileSync(path, 'utf-8')

  // ファイル内には他の確認モーダル（confirmModal.cancellationFee.message 等）にも
  // "message" キーがあるため、cancellationFeeWaive ブロック以降に絞ってから抽出する。
  const blockStart = source.indexOf('"cancellationFeeWaive"')
  if (blockStart < 0) {
    throw new Error('cancellationFeeWaive ブロックが ja/recruitment.ts に見つからない')
  }
  const block = source.slice(blockStart)

  const match = block.match(/"message":\s*"((?:[^"\\]|\\.)*)"/)
  if (!match) {
    throw new Error('confirmDialog.message が ja/recruitment.ts に見つからない')
  }
  return match[1].replace(/\\n/g, '\n')
}

describe('recruitment.cancellationFeeWaive.confirmDialog.message の文言方針', () => {
  const message = extractMessage()

  it('メッセージが実際に抽出できている（前提条件）', () => {
    expect(message.length).toBeGreaterThan(10)
    expect(message).toContain('{amount}')
  })

  it('「解除されます」という言い切りの肯定形を含まない', () => {
    expect(message).not.toContain('解除されます')
  })

  it('「残っている場合」に続く「解除されません」という否定形を含む', () => {
    expect(message).toMatch(/残っている場合、?.*解除されません/)
  })

  it('1文目に不可逆であることが書かれている（「取り消せません」）', () => {
    expect(message).toContain('取り消せません')
  })

  it('1文目（不可逆）が2文目（条件付き）より前に来る（順序固定）', () => {
    const irreversibleIndex = message.indexOf('取り消せません')
    const conditionalIndex = message.indexOf('残っている場合')
    expect(irreversibleIndex).toBeGreaterThan(-1)
    expect(conditionalIndex).toBeGreaterThan(-1)
    expect(irreversibleIndex).toBeLessThan(conditionalIndex)
  })
})
