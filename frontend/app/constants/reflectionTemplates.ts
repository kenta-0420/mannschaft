/**
 * F06.5 振り返りテンプレート（入力補助プリセット・§2.3.1）。
 *
 * BE はテンプレートを関知せず structured_content を受け取るのみ。
 * テンプレートは「初期見出し・推奨記入欄」の違いに過ぎないため、フロントの静的プリセット＋i18n で持つ。
 * ラベル・説明・初期見出し文言は i18n（reflection.template.* / reflection.template.preset.*）で参照する。
 *
 * Phase 4 追加: VOCAB テンプレ・sectionPresets（type 付き section）・buildInitialSections。
 */
import type { ReflectionSourceType, ReflectionSection } from '~/types/reflection'
import { emptySection, emptyCard } from '~/types/reflection'

/** テンプレートキー（UI 選択肢）。 */
export type ReflectionTemplateKey = 'NORMAL' | 'DIARY' | 'WORK' | 'CLASS' | 'VOCAB'

/** section の初期プリセット（type 付き・TERM_CARD 対応）。 */
export interface ReflectionTemplateSectionPreset {
  type: 'OUTLINE' | 'TERM_CARD'
  headingKey: string
}

/**
 * テンプレート定義。
 *
 * - `sourceType`: テーマ作成時に既定で選ばれる source_type。
 * - `sectionHeadingKeys`: 初期 section の見出しに使う i18n キー（reflection.template.preset.*）。
 *   空配列なら見出しなしの自由記述（NORMAL）。
 * - `sectionPresets`: type 付き section プリセット（あればこちらを優先）。
 */
export interface ReflectionTemplateDef {
  key: ReflectionTemplateKey
  /** ラベル i18n キー（reflection.template.{key}） */
  labelKey: string
  /** 説明 i18n キー */
  descKey: string
  sourceType: ReflectionSourceType
  /** 初期 section 見出しの i18n キー一覧（順序どおりに section を生成） */
  sectionHeadingKeys: string[]
  /** type 付き section プリセット（あればこちらを優先）。 */
  sectionPresets?: ReflectionTemplateSectionPreset[]
}

export const REFLECTION_TEMPLATES: ReflectionTemplateDef[] = [
  {
    key: 'NORMAL',
    labelKey: 'reflection.template.NORMAL',
    descKey: 'reflection.template.normal_desc',
    sourceType: 'FREE',
    sectionHeadingKeys: [],
  },
  {
    key: 'DIARY',
    labelKey: 'reflection.template.DIARY',
    descKey: 'reflection.template.diary_desc',
    sourceType: 'DIARY',
    sectionHeadingKeys: [
      'reflection.template.preset.diary.event',
      'reflection.template.preset.diary.feeling',
      'reflection.template.preset.diary.tomorrow',
    ],
  },
  {
    key: 'WORK',
    labelKey: 'reflection.template.WORK',
    descKey: 'reflection.template.work_desc',
    sourceType: 'PROJECT',
    sectionHeadingKeys: [
      'reflection.template.preset.work.result',
      'reflection.template.preset.work.issue',
      'reflection.template.preset.work.learning',
      'reflection.template.preset.work.next',
    ],
  },
  {
    key: 'CLASS',
    labelKey: 'reflection.template.CLASS',
    descKey: 'reflection.template.class_desc',
    sourceType: 'SUBJECT',
    sectionHeadingKeys: [
      'reflection.template.preset.class.learned',
      'reflection.template.preset.class.stumble',
      'reflection.template.preset.class.keypoint',
    ],
  },
  {
    key: 'VOCAB',
    labelKey: 'reflection.template.VOCAB',
    descKey: 'reflection.template.vocab_desc',
    sourceType: 'SUBJECT',
    sectionHeadingKeys: [],
    sectionPresets: [
      { type: 'TERM_CARD', headingKey: 'reflection.template.preset.vocab.cards' },
    ],
  },
]

/** source_type から対応するテンプレートキーを引く（テーマ編集時のプリセット推定用）。 */
export function templateKeyForSourceType(sourceType: ReflectionSourceType | null | undefined): ReflectionTemplateKey {
  switch (sourceType) {
    case 'DIARY':
      return 'DIARY'
    case 'PROJECT':
      return 'WORK'
    case 'SUBJECT':
      return 'CLASS'
    default:
      return 'NORMAL'
  }
}

/**
 * テンプレートから初期 section 一覧を生成する。
 * sectionPresets がある場合はそちらを使い type 付き section を生成。
 * 無ければ sectionHeadingKeys から OUTLINE section を生成（後方互換）。
 */
export function buildInitialSections(
  template: ReflectionTemplateDef,
  t: (key: string) => string,
): ReflectionSection[] {
  if (template.sectionPresets && template.sectionPresets.length > 0) {
    return template.sectionPresets.map(preset => {
      const heading = t(preset.headingKey)
      if (preset.type === 'TERM_CARD') {
        return { heading, subsections: [], type: 'TERM_CARD' as const, cards: [emptyCard()] }
      }
      return emptySection(heading)
    })
  }
  return template.sectionHeadingKeys.map(k => emptySection(t(k)))
}
