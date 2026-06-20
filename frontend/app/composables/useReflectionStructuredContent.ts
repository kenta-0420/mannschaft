/**
 * F06.5 structured_content（§2.3）と生成型 JsonNode の境界変換ヘルパー。
 *
 * 生成型では structured_content / recalled_content は `JsonNode = Record<string, never>`（opaque）に
 * なるため、UI 用の構造型 ReflectionStructuredContent との相互変換をここに集約する（any を使わず
 * 既知の構造へ正規化する。欠損フィールドは空で補完する＝表示側の防御）。
 */
import {
  type ReflectionStructuredContent,
  type ReflectionSection,
  type ReflectionSubsection,
  emptyStructuredContent,
} from '~/types/reflection'

/** 生成型の opaque JSON ノード（API の structured_content / recalled_content の型）。 */
export type JsonNode = Record<string, unknown>

function asString(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function normalizeSubsection(raw: unknown): ReflectionSubsection {
  const o = (raw ?? {}) as Record<string, unknown>
  return {
    sub_heading: asString(o.sub_heading),
    detail: asString(o.detail),
    supplement: asString(o.supplement),
  }
}

function normalizeSection(raw: unknown): ReflectionSection {
  const o = (raw ?? {}) as Record<string, unknown>
  const subs = Array.isArray(o.subsections) ? o.subsections.map(normalizeSubsection) : []
  return {
    heading: asString(o.heading),
    subsections: subs,
  }
}

export function useReflectionStructuredContent() {
  /** JsonNode（API 応答）→ UI 構造型。欠損は空で補完。 */
  function toStructured(node: JsonNode | null | undefined): ReflectionStructuredContent {
    if (!node || typeof node !== 'object') return emptyStructuredContent()
    const o = node as Record<string, unknown>
    return {
      main_theme: asString(o.main_theme),
      sections: Array.isArray(o.sections) ? o.sections.map(normalizeSection) : [],
      free_note: asString(o.free_note),
    }
  }

  /** UI 構造型 → API へ送る JsonNode（生成型は opaque ゆえ cast で詰める）。 */
  function toJsonNode(content: ReflectionStructuredContent): JsonNode {
    return {
      main_theme: content.main_theme,
      sections: content.sections.map(s => ({
        heading: s.heading,
        subsections: s.subsections.map(sub => ({
          sub_heading: sub.sub_heading,
          detail: sub.detail,
          supplement: sub.supplement,
        })),
      })),
      free_note: content.free_note,
    }
  }

  return { toStructured, toJsonNode }
}
