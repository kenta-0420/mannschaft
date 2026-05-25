/**
 * F01.10 マイページ履歴書・職務経歴書 — フロントエンド型定義
 *
 * バックエンド API（/api/v1/resumes）のレスポンス・リクエスト型。
 * types/generated/ は自動生成のため、ここに手動型定義を配置する。
 */

/** 元号フォーマット（西暦 / 和暦） */
export type EraFormat = 'WESTERN' | 'JAPANESE'

/** 書類種別 */
export type DocumentType = 'rirekisho' | 'shokumukeirekisho'

/** 出力形式 */
export type OutputFormat = 'pdf' | 'excel'

/** スキル習熟度 */
export type SkillLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT'

/**
 * 履歴書バージョンのサマリ（一覧表示用）。
 * GET /api/v1/resumes のレスポンス要素。
 */
export interface ResumeSummary {
  id: string
  title: string
  hasPhoto: boolean
  eraFormat: EraFormat
  updatedAt: string
}

/**
 * 学歴エントリ。
 * PUT /api/v1/resumes/{id} のリクエスト・レスポンスに含まれる。
 */
export interface ResumeEducation {
  id?: string
  entryYear: number
  entryMonth: number | null
  description: string
  displayOrder: number
}

/**
 * 職歴エントリ。
 * 履歴書（簡易）と職務経歴書（詳細）の兼用テーブル。
 * include_in_rirekisho / include_in_shokumukeireki で出し分けを制御。
 */
export interface ResumeCareer {
  id?: string
  entryYear: number
  entryMonth: number | null
  endYear: number | null
  endMonth: number | null
  isCurrent: boolean
  companyName: string
  department: string | null
  employmentType: string | null
  businessSummary: string | null
  jobDescription: string | null
  achievements: string | null
  includeInRirekisho: boolean
  includeInShokumukeireki: boolean
  displayOrder: number
}

/**
 * 免許・資格エントリ。
 */
export interface ResumeQualification {
  id?: string
  acquiredYear: number
  acquiredMonth: number | null
  name: string
  note: string | null
  displayOrder: number
}

/**
 * 活かせるスキル（職務経歴書用・任意）。
 * skills_summary（散文）とは役割が異なり、構造化した個別スキルの一覧。
 */
export interface ResumeSkill {
  id?: string
  skillName: string
  level: SkillLevel | null
  description: string | null
  displayOrder: number
}

/**
 * 履歴書のフル詳細（エディタ・保存用）。
 * GET /api/v1/resumes/{id} のレスポンス。
 * PUT /api/v1/resumes/{id} のリクエストにも使用（Partial<ResumeDetail>）。
 */
export interface ResumeDetail {
  id: string
  title: string
  eraFormat: EraFormat
  photoUrl: string | null
  currentAddress: string | null
  currentAddressKana: string | null
  contactAddress: string | null
  contactAddressKana: string | null
  contactPhone: string | null
  contactEmail: string | null
  motivation: string | null
  selfPr: string | null
  personalRequest: string | null
  commuteMinutes: number | null
  dependentsCount: number | null
  hasSpouse: boolean | null
  spouseSupport: boolean | null
  careerSummary: string | null
  skillsSummary: string | null
  /** 楽観ロック用バージョン。PUT時にこの値をリクエストに含める */
  version: number
  educations: ResumeEducation[]
  careers: ResumeCareer[]
  qualifications: ResumeQualification[]
  skills: ResumeSkill[]
}

/**
 * 正式出力（/export）のレスポンス。
 * presigned URL と有効期限を返す。
 */
export interface ResumeExportResponse {
  downloadUrl: string
  fileName: string
  expiresAt: string
}

/**
 * 新規作成リクエスト。
 * title を省略した場合はサーバが「下書き YYYY-MM-DD」を自動採番する。
 */
export interface ResumeCreateRequest {
  title?: string
}

/**
 * フル一括保存リクエスト（PUT）。
 * 送られた集合が正であり、リクエストに含まれなかった既存子は論理削除される（宣言的置換）。
 */
export type ResumeFullSaveRequest = Omit<Partial<ResumeDetail>, 'id' | 'photoUrl'>
