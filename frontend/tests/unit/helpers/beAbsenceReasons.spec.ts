import { describe, it, expect } from 'vitest'
import { parseJavaEnumConstants, readBeAbsenceReasons } from './beAbsenceReasons'

/** 契約テスト用ヘルパ（BE の enum 定数抽出）自体のテスト。 */
const parse = (body: string): string[] => parseJavaEnumConstants(`public enum E {${body}}`, 'E')

describe('parseJavaEnumConstants', () => {
  it('引数なし', () => {
    expect(parse('A, B, C')).toEqual(['A', 'B', 'C'])
  })

  it('引数付き（数値・文字列・複数引数・カンマ入り文字列）', () => {
    expect(parse('SICK(1), INJURY(2), NAME("病気"), TWO(1, "a,b"), ESC("q\\")")')).toEqual([
      'SICK',
      'INJURY',
      'NAME',
      'TWO',
      'ESC',
    ])
  })

  it('本体付き', () => {
    expect(parse('A { int f() { return 1; } }, B { void g() {} }')).toEqual(['A', 'B'])
  })

  it('引数付き・本体付き・引数なしの混在', () => {
    expect(parse('A, B(1), C { int f() { return 1; } }, D("x") { }, E')).toEqual([
      'A',
      'B',
      'C',
      'D',
      'E',
    ])
  })

  it('コメント入り（行・ブロック・Javadoc・文字列内の // は保持）', () => {
    expect(
      parse(`
        /** 体調不良 */
        SICK, // 行コメント, X
        /* ブロック, Y */ INJURY("http://a"),
        OTHER
      `),
    ).toEqual(['SICK', 'INJURY', 'OTHER'])
  })

  it('末尾カンマ', () => {
    expect(parse('A, B,')).toEqual(['A', 'B'])
    expect(parse('A, B,;')).toEqual(['A', 'B'])
  })

  it('; の後にフィールドとメソッドがある', () => {
    expect(
      parse(`
        A(1), B(2);
        private final int code;
        E(int code) { this.code = code; }
        int code() { return code, ; }
        static final String X = "C, D";
      `),
    ).toEqual(['A', 'B'])
  })

  it('注釈付き定数', () => {
    expect(parse('@Deprecated A, @Foo(x = 1) B')).toEqual(['A', 'B'])
  })

  it('対象 enum が無ければ空配列', () => {
    expect(parseJavaEnumConstants('class X {}', 'E')).toEqual([])
  })
})

describe('readBeAbsenceReasons', () => {
  it('現行の AbsenceReason.java から8件取れる', () => {
    expect([...readBeAbsenceReasons()].sort()).toEqual(
      [
        'BEREAVEMENT',
        'FAMILY_REASON',
        'INFECTIOUS_DISEASE',
        'INJURY',
        'MENTAL_HEALTH',
        'OFFICIAL_BUSINESS',
        'OTHER',
        'SICK',
      ].sort(),
    )
  })
})
