import { ofetch } from 'ofetch'
import type {
  BulletinAttachment,
  BulletinAttachmentPresignRequest,
  BulletinAttachmentPresignResponse,
  BulletinAttachmentConfirmRequest,
  BulletinAttachmentDownloadUrlResponse,
  BulletinAttachmentTargetType,
} from '~/types/bulletin'

/**
 * 掲示板添付ファイル API（F05.1 §6 presigned URL 方式 A）。
 *
 * - presign: POST /api/v1/bulletin/attachments/upload-url
 * - confirm: POST /api/v1/bulletin/attachments
 * - listThread: GET /api/v1/bulletin/threads/{threadId}/attachments
 * - listReply:  GET /api/v1/bulletin/replies/{replyId}/attachments
 * - downloadUrl: GET /api/v1/bulletin/attachments/{id}/download-url
 * - remove: DELETE /api/v1/bulletin/attachments/{id}
 *
 * R2 直 PUT は ofetch を直接使用し、credentials なし・Content-Type は presign 時と一致させる。
 * 先行実装の参照: SkillForm.vue（`$fetch(uploadUrl, { method:'PUT', body:file, headers:{...} })`）。
 */
export function useBulletinAttachments() {
  const api = useApi()

  /**
   * presigned URL を発行する。
   * BE がスコープ認可・MIME ホワイトリスト（SVG 不可）・サイズ（10MB 以下）・上限（5件）を検証する。
   */
  async function presign(
    request: BulletinAttachmentPresignRequest,
  ): Promise<BulletinAttachmentPresignResponse> {
    const res = await api<{ data: BulletinAttachmentPresignResponse }>(
      '/api/v1/bulletin/attachments/upload-url',
      { method: 'POST', body: request },
    )
    return res.data
  }

  /**
   * R2 に直接 PUT アップロードする。
   * - credentials なし（R2 の presigned URL は署名内に認証情報を含むため Cookie は不要）
   * - Content-Type を presign 時と完全一致させる（不一致は署名検証失敗になる）
   * - 進捗は onProgress コールバックで通知する（省略可）
   */
  async function uploadToR2(
    uploadUrl: string,
    file: File,
    onProgress?: (percent: number) => void,
  ): Promise<void> {
    if (onProgress) {
      // XHR で進捗通知
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest()
        xhr.open('PUT', uploadUrl, true)
        xhr.setRequestHeader('Content-Type', file.type)
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable) {
            onProgress(Math.round((e.loaded / e.total) * 100))
          }
        }
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            resolve()
          } else {
            reject(new Error(`R2 PUT failed: ${xhr.status}`))
          }
        }
        xhr.onerror = () => reject(new Error('R2 PUT network error'))
        xhr.send(file)
      })
    } else {
      // ofetch で PUT（進捗不要の場合）
      await ofetch(uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': file.type },
      })
    }
  }

  /**
   * 添付ファイルを確定（メタデータ登録）する。
   * R2 への PUT 完了後に呼び出す。
   */
  async function confirm(request: BulletinAttachmentConfirmRequest): Promise<BulletinAttachment> {
    const res = await api<{ data: BulletinAttachment }>(
      '/api/v1/bulletin/attachments',
      { method: 'POST', body: request },
    )
    return res.data
  }

  /**
   * スレッドの添付ファイル一覧を取得する。
   */
  async function listThreadAttachments(threadId: number): Promise<BulletinAttachment[]> {
    const res = await api<{ data: BulletinAttachment[] }>(
      `/api/v1/bulletin/threads/${threadId}/attachments`,
    )
    return res.data ?? []
  }

  /**
   * 返信の添付ファイル一覧を取得する。
   */
  async function listReplyAttachments(replyId: number): Promise<BulletinAttachment[]> {
    const res = await api<{ data: BulletinAttachment[] }>(
      `/api/v1/bulletin/replies/${replyId}/attachments`,
    )
    return res.data ?? []
  }

  /**
   * 添付ファイルのダウンロード用 presigned URL を発行する。
   * 生 fileKey は返却されない（IDOR 防止）。
   */
  async function getDownloadUrl(id: number): Promise<BulletinAttachmentDownloadUrlResponse> {
    const res = await api<{ data: BulletinAttachmentDownloadUrlResponse }>(
      `/api/v1/bulletin/attachments/${id}/download-url`,
    )
    return res.data
  }

  /**
   * 添付ファイルを削除する（本人 or モデレーター/ADMIN）。
   */
  async function remove(id: number): Promise<void> {
    await api(`/api/v1/bulletin/attachments/${id}`, { method: 'DELETE' })
  }

  /**
   * ファイル1件を presign → R2 PUT → confirm の3ステップでアップロードする。
   * @param targetType 添付対象種別（THREAD / REPLY）
   * @param targetId   添付対象の ID
   * @param file       アップロードするファイル
   * @param onProgress 進捗コールバック（省略可）
   * @returns 確定済み BulletinAttachment
   */
  async function uploadFile(
    targetType: BulletinAttachmentTargetType,
    targetId: number,
    file: File,
    onProgress?: (percent: number) => void,
  ): Promise<BulletinAttachment> {
    // (1) presign
    const presignRes = await presign({
      targetType,
      targetId,
      fileName: file.name,
      contentType: file.type,
      fileSize: file.size,
    })

    // (2) R2 直 PUT（Content-Type を presign 時と一致させる）
    await uploadToR2(presignRes.uploadUrl, file, onProgress)

    // (3) 確定
    return confirm({
      targetType,
      targetId,
      fileKey: presignRes.fileKey,
      originalFilename: file.name,
      fileSize: file.size,
      contentType: file.type,
    })
  }

  return {
    presign,
    uploadToR2,
    confirm,
    listThreadAttachments,
    listReplyAttachments,
    getDownloadUrl,
    remove,
    uploadFile,
  }
}
