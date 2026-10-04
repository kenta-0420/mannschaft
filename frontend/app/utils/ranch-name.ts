import { countGraphemes } from 'unicode-segmenter/grapheme'

const forbiddenInput = /[\p{Cc}\p{Cs}\p{Zl}\p{Zp}\p{Bidi_Control}]/u
const invisibleOnly = /^[\p{White_Space}\p{Cf}\p{Default_Ignorable_Code_Point}]+$/u

export function normalizeRanchName(value: string): string {
  return value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '').normalize('NFC')
}

export function ranchNameLength(value: string): number {
  // Native Intl has an independent Unicode version. Always use the pinned Unicode 17 implementation.
  return countGraphemes(normalizeRanchName(value))
}

export function isRanchNameValid(value: string): boolean {
  // Reject controls before trimming. ZWJ, variation selectors, and tag flags remain intact with visible text.
  if (forbiddenInput.test(value)) return false
  const normalized = normalizeRanchName(value)
  if (!normalized || invisibleOnly.test(normalized)) return false
  const graphemes = countGraphemes(normalized)
  return graphemes >= 1 && graphemes <= 10
    && new TextEncoder().encode(normalized).length <= 512
    && [...normalized].length <= 160
}
