import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 手書き型 `types/receipt.ts` と BE の実レスポンスのフィールド名一致を機械的に検査する番人。
 *
 * 置いた理由（CMP-260907-0915）: 手書き型が `totalAmount` という BE に存在しないフィールドを
 * 「必ずある number」と宣言していたため、`npm run typecheck` は通り、
 * 一覧の金額列が実行時に `Cannot read properties of undefined (reading 'toLocaleString')` を
 * 全行で投げるまで誰も気づけなかった。型が嘘をついている限り型検査は防波堤にならないので、
 * 「嘘そのもの」を検出する検査をここに置く。
 *
 * 正本は `types/generated/index.ts`（`docs/openapi.json` から openapi-typescript が生成する
 * 自動生成物。手動編集禁止）の `components.schemas`。生成型への全面移行は別途ブロック中のため、
 * 手書き型は残したまま、名前の食い違いだけをここで突き合わせる。
 */

// 改行コードは環境で揺れる（Windows の autocrlf で CRLF になる）ため、読み込み時に揃える。
function read(relativePath: string): string {
  return readFileSync(resolve(process.cwd(), relativePath), 'utf8').replace(/\r\n/g, '\n')
}

const generatedSource = read('app/types/generated/index.ts')
const handwrittenSource = read('app/types/receipt.ts')

/** 生成型の `components.schemas.<name>` が持つプロパティ名を取り出す。 */
function generatedSchemaFields(name: string): string[] {
  const header = `\n        ${name}: {\n`
  const start = generatedSource.indexOf(header)
  expect(start, `生成型に schema ${name} が見つからない`).toBeGreaterThan(-1)
  const bodyStart = start + header.length
  const end = generatedSource.indexOf('\n        };', bodyStart)
  expect(end, `生成型の schema ${name} の終端が見つからない`).toBeGreaterThan(-1)
  const body = generatedSource.slice(bodyStart, end)
  // ネストしたオブジェクト定義は領収書系スキーマには無い（参照は $ref 相当の components[...]）。
  return [...body.matchAll(/^ {12}(\w+)\??:/gm)].map(m => m[1] as string).sort()
}

/** 手書き型の `export interface <name> { ... }` が持つプロパティ名を取り出す。 */
function handwrittenInterfaceFields(name: string): string[] {
  const header = `export interface ${name} {\n`
  const start = handwrittenSource.indexOf(header)
  expect(start, `手書き型に interface ${name} が見つからない`).toBeGreaterThan(-1)
  const bodyStart = start + header.length
  const end = handwrittenSource.indexOf('\n}', bodyStart)
  const body = handwrittenSource.slice(bodyStart, end)
  return [...body.matchAll(/^ {2}(\w+)\??:/gm)].map(m => m[1] as string).sort()
}

describe('領収書の手書き型と BE レスポンスのフィールド名一致（CMP-260907-0915）', () => {
  it('ReceiptResponse は BE の実フィールドと過不足なく一致する', () => {
    expect(handwrittenInterfaceFields('ReceiptResponse')).toEqual(
      generatedSchemaFields('ReceiptResponse'),
    )
  })

  it('MyReceiptResponse は BE の実フィールドと過不足なく一致する', () => {
    expect(handwrittenInterfaceFields('MyReceiptResponse')).toEqual(
      generatedSchemaFields('MyReceiptResponse'),
    )
  })

  it('金額は amount であり、BE に存在しない totalAmount を宣言していない', () => {
    const fields = handwrittenInterfaceFields('ReceiptResponse')
    expect(fields).toContain('amount')
    expect(fields).not.toContain('totalAmount')
  })

  it('発行リクエストの金額も BE `CreateReceiptRequest` に合わせて amount である', () => {
    const fields = handwrittenInterfaceFields('IssueReceiptRequest')
    expect(fields).toContain('amount')
    expect(fields).not.toContain('totalAmount')
    // BE が受けないフィールドを送っても黙って捨てられるため、送る側の型にも置かない。
    expect(fields).not.toContain('notes')
    expect(generatedSchemaFields('CreateReceiptRequest')).toEqual(expect.arrayContaining(fields))
  })
})
