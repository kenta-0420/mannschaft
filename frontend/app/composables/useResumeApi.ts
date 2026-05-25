/**
 * F01.10 マイページ履歴書・職務経歴書 — API composable
 *
 * /api/v1/resumes/** の全エンドポイントを提供する。
 * - 一覧取得・フル取得・作成・フル一括保存（PUT）・ヘッダ部分更新（PATCH）・論理削除・複製
 * - 証明写真アップロード・削除
 * - プレビュー（インライン byte レスポンス）・正式出力（presigned URL）
 *
 * API 通信は useApi()（$fetch ラッパー）を使用する。
 * useFetch はページ初期データの SSR 取得に使用し、ユーザー操作による送受信は $fetch（useApi）を使う。
 */
import type {
  ResumeSummary,
  ResumeDetail,
  ResumeExportResponse,
  ResumeCreateRequest,
  ResumeFullSaveRequest,
  DocumentType,
  OutputFormat,
} from '~/types/resume'
import type { ApiResponse } from '~/types/api'

export function useResumeApi() {
  const api = useApi()

  // === 一覧取得 ===
  /** 自分の履歴書バージョン一覧を取得する（サマリ） */
  async function listResumes(): Promise<ApiResponse<ResumeSummary[]>> {
    return api<ApiResponse<ResumeSummary[]>>('/api/v1/resumes')
  }

  // === フル取得 ===
  /** 指定 ID の履歴書をフル取得する（学歴・職歴・資格・スキル含む） */
  async function getResume(id: string): Promise<ApiResponse<ResumeDetail>> {
    return api<ApiResponse<ResumeDetail>>(`/api/v1/resumes/${id}`)
  }

  // === 新規作成 ===
  /**
   * 新規履歴書バージョンを作成する。
   * title を省略した場合はサーバが「下書き YYYY-MM-DD」を自動採番する。
   */
  async function createResume(req?: ResumeCreateRequest): Promise<ApiResponse<ResumeDetail>> {
    return api<ApiResponse<ResumeDetail>>('/api/v1/resumes', {
      method: 'POST',
      body: req ?? {},
    })
  }

  // === フル一括保存（PUT）===
  /**
   * ヘッダ + 全子要素を宣言的置換で一括保存する。
   * リクエストに含まれなかった既存子要素は論理削除される（冪等）。
   * リクエストボディに version を含めること（楽観ロック）。
   * 競合時は 409 / RESUME_010 が返る。
   */
  async function saveResume(id: string, data: ResumeFullSaveRequest): Promise<ApiResponse<ResumeDetail>> {
    return api<ApiResponse<ResumeDetail>>(`/api/v1/resumes/${id}`, {
      method: 'PUT',
      body: data,
    })
  }

  // === ヘッダ部分更新（PATCH）===
  /** ヘッダ項目のみ部分更新する（子要素は対象外） */
  async function patchResume(id: string, data: Partial<ResumeDetail>): Promise<ApiResponse<ResumeDetail>> {
    return api<ApiResponse<ResumeDetail>>(`/api/v1/resumes/${id}`, {
      method: 'PATCH',
      body: data,
    })
  }

  // === 論理削除 ===
  /** 履歴書バージョンを論理削除する */
  async function deleteResume(id: string): Promise<void> {
    return api(`/api/v1/resumes/${id}`, { method: 'DELETE' })
  }

  // === 複製 ===
  /**
   * 指定バージョンを子要素ごと複製する。
   * title は「{元タイトル} (コピー)」になる。証明写真は新キーに独立コピーされる。
   */
  async function duplicateResume(id: string): Promise<ApiResponse<ResumeDetail>> {
    return api<ApiResponse<ResumeDetail>>(`/api/v1/resumes/${id}/duplicate`, {
      method: 'POST',
    })
  }

  // === 証明写真アップロード ===
  /**
   * 証明写真をアップロードする（multipart/form-data）。
   * サーバ側で EXIF/GPS 除去・寸法上限リサイズが行われる。
   * JPEG / PNG のみ対応（最大 5MB）。
   */
  async function uploadPhoto(id: string, file: File): Promise<ApiResponse<{ photoUrl: string }>> {
    const formData = new FormData()
    formData.append('file', file)
    return api<ApiResponse<{ photoUrl: string }>>(`/api/v1/resumes/${id}/photo`, {
      method: 'POST',
      body: formData,
    })
  }

  // === 証明写真削除 ===
  /** 証明写真を削除する */
  async function deletePhoto(id: string): Promise<void> {
    return api(`/api/v1/resumes/${id}/photo`, { method: 'DELETE' })
  }

  // === プレビュー（byte ストリーミング）===
  /**
   * 書類プレビューを取得する。
   * R2 に保存せずインライン byte レスポンスを返す（レート制限 120 回 / 時）。
   * Blob URL を作成して iframe / 新規タブで表示する。
   */
  async function previewResume(id: string, type: DocumentType, format: OutputFormat): Promise<Blob> {
    return $fetch<Blob>(`/api/v1/resumes/${id}/preview?type=${type}&format=${format}`, {
      method: 'GET',
      responseType: 'blob',
    })
  }

  // === 正式出力（presigned URL）===
  /**
   * 書類を正式出力する。R2 に永続保存し presigned URL を返す（レート制限 30 回 / 時）。
   * 監査ログ RESUME_EXPORTED が記録される。
   */
  async function exportResume(
    id: string,
    type: DocumentType,
    format: OutputFormat,
  ): Promise<ApiResponse<ResumeExportResponse>> {
    return api<ApiResponse<ResumeExportResponse>>(
      `/api/v1/resumes/${id}/export?type=${type}&format=${format}`,
    )
  }

  return {
    listResumes,
    getResume,
    createResume,
    saveResume,
    patchResume,
    deleteResume,
    duplicateResume,
    uploadPhoto,
    deletePhoto,
    previewResume,
    exportResume,
  }
}
