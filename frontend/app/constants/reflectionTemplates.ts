/**
 * F06.5 振り返りテンプレート（入力補助プリセット・§2.3.1）。
 *
 * BE はテンプレートを関知せず structured_content を受け取るのみ。
 * テンプレートは「初期見出し・推奨記入欄」の違いに過ぎないため、フロントの静的プリセット＋i18n で持つ。
 * ラベル・説明・初期見出し文言は i18n（reflection.template.* / reflection.template.preset.*）で参照する。
 */
import type { ReflectionSourceType } from '~/types/reflection'

/** テンプレートキー（UI 選択肢）。 */
export type ReflectionTemplateKey = 'NORMAL' | 'DIARY' | 'WORK' | 'CLASS'

/**
 * テンプレート定義。
 *
 * - `sourceType`: テーマ作成時に既定で選ばれる source_type。
 * - `sectionHeadingKeys`: 初期 section の見出しに使う i18n キー（reflection.template.preset.*）。
 *   空配列なら見出しなしの自由記述（NORMAL）。
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
