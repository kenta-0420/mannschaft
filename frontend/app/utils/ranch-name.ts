export function normalizeRanchName(value: string): string { return value.trim().normalize('NFC') }
export function ranchNameLength(value: string): number {
 return [...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(normalizeRanchName(value))].length
}
export function isRanchNameValid(value: string): boolean {
 const normalized = normalizeRanchName(value)
 // ZWJは絵文字の書記素結合を許す。不可視のみ・制御・改行は拒否する。
 return !/[\p{Cc}\p{Zl}\p{Zp}\u200B\u200C\uFEFF\u202A-\u202E\u2066-\u2069]/u.test(value)
   && /[^\p{Cf}\p{Z}]/u.test(normalized) && ranchNameLength(normalized) >= 1 && ranchNameLength(normalized) <= 10
   && new TextEncoder().encode(normalized).length <= 512
   && [...normalized].length <= 160
}
