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
 * <p><b>6 言語すべてを対象にする。</b> 日本語だけを見ていると、他 5 言語が
 * 「申込制限が解除されます」という言い切り型に書き換えられても検出できない——利用者の
 * ロケール次第で誤った期待を持たされるのだから、守るべき方針は日本語に固有ではない。</p>
 *
 * <p>ロケールファイル（{@code recruitment.ts}）は Nuxt i18n のビルド変換対象であり、
 * vitest から素で import すると変換後の形（プレーンオブジェクトではない）になり値が
 * 正しく取れない。そのため本テストは <b>ソースファイルをテキストとして読み、正規表現で
 * message フィールドの値を抜き出す</b>——変換パイプラインに依存せず、実際にコミットされた
 * 文言そのものを検証する。</p>
 *
 * 方針（設計書 §12.1）:
 *   1. 申込制限が「解除される」と言い切る文言を置いてはならない
 *   2. 1 文目に必ず起こること（債権の放棄・不可逆）、2 文目に条件付きであることを書く。順序を入れ替えない
 *   3. 肯定形ではなく「残っている場合は解除されません」の否定形で書く
 */

/** 各ロケールで「その言語ならこう書かれているはず」を表す検査条件。 */
interface LocalePolicy {
  /** 1 文目にある「取り消せない（不可逆）」を表す語。 */
  irreversible: RegExp
  /** 2 文目にある「他に未払いが残っている場合」という条件を表す語。 */
  conditional: RegExp
  /** 2 文目が否定形（解除されない）であることを表す語。 */
  negated: RegExp
  /**
   * 置いてはならない言い切りの肯定形。
   * 否定形（「解除されません」等）に一致しないよう、否定語を含まないことを前提に組む。
   */
  forbidden: RegExp
}

const POLICIES: Record<string, LocalePolicy> = {
  ja: {
    irreversible: /取り消せません/,
    conditional: /残っている場合/,
    negated: /解除されません/,
    // 「解除されます」という言い切り（「解除されません」には一致しない）。
    forbidden: /解除されます/,
  },
  en: {
    irreversible: /cannot be undone/i,
    conditional: /if this user has other unpaid/i,
    negated: /will not be lifted/i,
    // 否定を伴わない "will be lifted" だけを禁止する。
    forbidden: /will be lifted/i,
  },
  zh: {
    irreversible: /无法撤销/,
    conditional: /如果该用户还有其他未支付/,
    negated: /将不会解除/,
    forbidden: /将会解除|将解除/,
  },
  ko: {
    irreversible: /되돌릴 수 없습니다/,
    conditional: /남아 있는 경우/,
    negated: /해제되지 않습니다/,
    forbidden: /해제됩니다/,
  },
  es: {
    irreversible: /no se puede deshacer/i,
    conditional: /si este usuario tiene otras tarifas de cancelaci/i,
    negated: /no se levantar/i,
    // 否定語 "no " を伴わない "se levantará" のみを禁止する。
    forbidden: /(?<!no )se levantará/i,
  },
  de: {
    irreversible: /nicht r(ü|ue)ckg(ä|ae)ngig gemacht werden/i,
    conditional: /wenn bei diesem nutzer weitere unbezahlte/i,
    negated: /nicht aufgehoben/i,
    // "nicht aufgehoben" に一致しない言い切りだけを禁止する。
    forbidden: /(?<!nicht )aufgehoben wird|wird die anmeldebeschr(ä|ae)nkung aufgehoben/i,
  },
}

function extractMessage(locale: string): string {
  // vitest はプロジェクトルート（frontend/）を cwd として実行される。
  const path = join(process.cwd(), `app/locales/${locale}/recruitment.ts`)
  const source = readFileSync(path, 'utf-8')

  // ファイル内には他の確認モーダル（confirmModal.cancellationFee.message 等）にも
  // "message" キーがあるため、cancellationFeeWaive ブロック以降に絞ってから抽出する。
  const blockStart = source.indexOf('"cancellationFeeWaive"')
  if (blockStart < 0) {
    throw new Error(`cancellationFeeWaive ブロックが ${locale}/recruitment.ts に見つからない`)
  }
  const block = source.slice(blockStart)

  const match = block.match(/"message":\s*"((?:[^"\\]|\\.)*)"/)
  // 捕獲グループが取れない＝文言が存在しないということであり、テストは失敗すべき。
  // 非 null アサーション（!）で黙らせると「文言が消えた」事故を緑のまま見逃す。
  const captured = match?.[1]
  if (captured === undefined) {
    throw new Error(`confirmDialog.message が ${locale}/recruitment.ts に見つからない`)
  }
  return captured.replace(/\\n/g, '\n')
}

describe('recruitment.cancellationFeeWaive.confirmDialog.message の文言方針（6言語）', () => {
  const locales = Object.keys(POLICIES)

  it('6言語すべてを検査対象にしている（対象漏れの番人）', () => {
    // ロケールを増やしたのに本テストへ足し忘れると、その言語だけ野放しになる。
    expect(locales).toEqual(expect.arrayContaining(['ja', 'en', 'zh', 'ko', 'es', 'de']))
    expect(locales).toHaveLength(6)
  })

  describe.each(locales)('%s', (locale) => {
    const policy = POLICIES[locale]
    if (policy === undefined) {
      // locales は POLICIES のキーから作っているため理論上到達しないが、
      // 到達したら検査条件が欠けているということなので、黙って素通りさせない。
      throw new Error(`${locale} の検査条件が POLICIES に無い`)
    }
    const message = extractMessage(locale)

    it('メッセージが実際に抽出できている（前提条件）', () => {
      expect(message.length).toBeGreaterThan(10)
      expect(message).toContain('{amount}')
    })

    it('申込制限が解除されると言い切る肯定形を含まない', () => {
      expect(message).not.toMatch(policy.forbidden)
    })

    it('「他に未払いが残っている場合」に続く否定形（解除されない）を含む', () => {
      expect(message).toMatch(policy.conditional)
      expect(message).toMatch(policy.negated)
    })

    it('1文目に不可逆であることが書かれている', () => {
      expect(message).toMatch(policy.irreversible)
    })

    it('1文目（不可逆）が2文目（条件付き）より前に来る（順序固定）', () => {
      const irreversibleIndex = message.search(policy.irreversible)
      const conditionalIndex = message.search(policy.conditional)
      expect(irreversibleIndex).toBeGreaterThan(-1)
      expect(conditionalIndex).toBeGreaterThan(-1)
      expect(irreversibleIndex).toBeLessThan(conditionalIndex)
    })
  })
})
