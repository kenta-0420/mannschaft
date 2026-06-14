/**
 * F08.10 ターン制（将棋/囲碁）対局結果・団体戦ボード・局面写真 API composable（6-④c 配線）。
 *
 * 6-④a で main にマージされた以下の BE エンドポイントへ FE を結線する
 * （sports/05_shogi.md §4 / §8.1 / sports/06_go.md §4 / 01 §B.1.2 / §B.6 / §B.7 / 03 §C.2a / §C.4 / §C.7a）:
 *
 *   PUT    /organizations/{orgId}/matches/{matchId}/result                  recordResult（対局結果・冪等）
 *   POST   /organizations/{orgId}/matches/{matchId}/boards                  createBoard（団体戦の子ボード作成）
 *   GET    /organizations/{orgId}/matches/{matchId}/boards                  listBoards（親 ID スコープ）
 *   POST   /organizations/{orgId}/matches/{matchId}/attachments/presign     presignAttachment（局面写真 presign）
 *   POST   /organizations/{orgId}/matches/{matchId}/attachments             confirmAttachment（局面写真 確定）
 *   GET    /organizations/{orgId}/matches/{matchId}/attachments             listAttachments（一覧）
 *   GET    .../attachments/{attachmentId}/download-url                      attachmentDownloadUrl（短命 DL URL）
 *   DELETE .../attachments/{attachmentId}                                   deleteAttachment（削除）
 *
 * 【前向きユニオン境界（6-④c）】
 * これらの BE DTO（MatchRecordTurnResultRequest / MatchRecordBoardCreateRequest /
 * MatchRecordAttachment*）は openapi-typescript 再生成が後続フェーズのため、生成型 `Schemas` に
 * 未反映である。よって本 composable 内に**手動の前向きユニオン型**を定義し、`useApi` の境界（各関数の
 * ジェネリック引数 1 箇所）で吸収する（any 禁止・後続の生成型一括再生成で本ファイルの手動型を撤去する）。
 * TODO: BE openapi 再生成後、本ファイルの Turn* 手動型を Schemas 由来へ置換する。
 *
 * R2 直 PUT は useBulletinAttachments と同じ presign 方式 A（ofetch 直叩き・credentials なし・
 * Content-Type を presign 時と一致）を踏襲する（01 §B.7）。
 */
import { ofetch } from 'ofetch'

// ===== 前向きユニオン型（生成型未反映・後続再生成で撤去） =====

/** 勝者サイド（HOME=先手/黒・AWAY=後手/白・null=引分け）。 */
export type TurnResultWinnerSide = 'HOME' | 'AWAY' | null

/** 対局結果記録リクエスト（BE MatchRecordTurnResultRequest 相当・引分時 winMethod は送らない）。 */
export interface MatchTurnResultRequestPayload {
  /** 勝者サイド（null=引分け）。 */
  winnerSide: TurnResultWinnerSide
  /** 勝ち方の enum 名（任意・引分時は省略＝BE の MATCH_028 を回避）。 */
  winMethod?: string | null
  /** 総手数（任意）。 */
  totalMoves?: number | null
}

/** 団体戦の子ボード作成リクエスト（BE MatchRecordBoardCreateRequest 相当）。 */
export interface MatchBoardCreateRequestPayload {
  /** ボード順（1=大将/主将 等・親内一意）。 */
  boardNumber: number
  /** 相手チーム ID（任意・親から継承する場合は省略）。 */
  opponentTeamId?: number | null
  /** 未登録相手名（任意）。 */
  opponentName?: string | null
}

/**
 * ターン制 match レスポンス（BE MatchDetailResponse 相当・ターン制拡張フィールドを含む前向きユニオン）。
 * 生成型 MatchDetailResponse には winMethod/totalMoves/parentMatchId/boardNumber が未反映のため手動定義する。
 */
export interface TurnMatchResponse {
  id?: string
  teamId?: number
  parentMatchId?: string | null
  boardNumber?: number | null
  homeScore?: number | null
  awayScore?: number | null
  winMethod?: string | null
  totalMoves?: number | null
  opponentName?: string | null
  opponentTeamId?: number | null
  status?: string | null
  canRecordTimeline?: boolean
  canEditMeta?: boolean
}

/** 局面写真 presign リクエスト（BE MatchRecordAttachmentPresignRequest 相当）。 */
export interface MatchAttachmentPresignRequestPayload {
  contentType: string
  fileSize: number
}

/** 局面写真 presign レスポンス（BE MatchRecordAttachmentPresignResponse 相当）。 */
export interface MatchAttachmentPresignResponsePayload {
  uploadUrl: string
  fileKey: string
  expiresInSeconds: number
}

/** 局面写真 確定リクエスト（BE MatchRecordAttachmentConfirmRequest 相当）。 */
export interface MatchAttachmentConfirmRequestPayload {
  fileKey: string
  originalFilename?: string | null
  contentType: string
  fileSize: number
}

/** 局面写真レスポンス（BE MatchRecordAttachmentResponse 相当）。 */
export interface MatchAttachmentResponsePayload {
  id: string
  matchId: string
  originalFilename?: string | null
  contentType?: string | null
  fileSize?: number | null
  createdBy?: number | null
  createdAt?: string | null
}

/** 局面写真 短命 DL URL レスポンス（BE MatchRecordAttachmentDownloadResponse 相当）。 */
export interface MatchAttachmentDownloadResponsePayload {
  downloadUrl: string
  expiresInSeconds: number
}

// ===== ヘルパー =====

/**
 * 引分時に winMethod を落とした対局結果ペイロードを構築する（🟡 MATCH_028 整合）。
 *
 * BE は winnerSide=null（引分）のとき winMethod 非 NULL を 400(MATCH_028) で弾く（責務分離・§4.2）。
 * 千日手/持碁の UI 選択（REPETITION/IMPASSE 等）が残っていても、引分確定時は winMethod を送信しない。
 *
 * @param winnerSide 勝者サイド（null=引分）
 * @param winMethod  勝ち方（任意・引分時は無視される）
 * @param totalMoves 総手数（任意）
 */
export function buildTurnResultPayload(
  winnerSide: TurnResultWinnerSide,
  winMethod: string | null | undefined,
  totalMoves: number | null | undefined,
): MatchTurnResultRequestPayload {
  if (winnerSide === null) {
    // 引分: winMethod は送らない（BE の MATCH_028 回避・両スコア 0）
    return { winnerSide: null, winMethod: null, totalMoves: totalMoves ?? null }
  }
  return {
    winnerSide,
    winMethod: winMethod ?? null,
    totalMoves: totalMoves ?? null,
  }
}

/** SVG はアップロード対象外（BE も弾くが FE でも事前ガード・01 §B.7）。 */
export function isUploadableImage(file: File): boolean {
  const type = file.type.toLowerCase()
  return type.startsWith('image/') && type !== 'image/svg+xml'
}

/** 局面写真の上限サイズ（10MB・BE と一致・事前ガード）。 */
export const MATCH_ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024

export function useMatchTurnApi() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const base = (orgId: number, matchId: string) =>
    `/api/v1/organizations/${orgId}/matches/${matchId}`

  // ─── 対局結果（PUT /result・冪等） ───

  /**
   * 対局結果を記録/更新する（勝者・勝ち方・総手数）。
   * BE が home/away_score（1-0/0-1/0-0）を確定し、子ボードなら親の勝ち星を再集計する。
   * status の COMPLETED 遷移は別途 changeStatus で行う（順位連携の MatchCompletedEvent 発火経路）。
   */
  async function recordResult(
    orgId: number,
    matchId: string,
    payload: MatchTurnResultRequestPayload,
  ): Promise<TurnMatchResponse> {
    try {
      // 前向きユニオン境界: 生成型未反映の Turn* 型を useApi のジェネリックで吸収する。
      const res = await api<{ data: TurnMatchResponse }>(`${base(orgId, matchId)}/result`, {
        method: 'PUT',
        body: payload,
      })
      return res.data
    } catch (err) {
      notification.error(t('match.turn.error.record_result_failed'))
      throw err
    }
  }

  // ─── 団体戦ボード（POST/GET /boards） ───

  /** 団体戦の子ボードを作成する（親配下・テナント/競技/記録モードは親から継承）。 */
  async function createBoard(
    orgId: number,
    matchId: string,
    payload: MatchBoardCreateRequestPayload,
  ): Promise<TurnMatchResponse> {
    try {
      const res = await api<{ data: TurnMatchResponse }>(`${base(orgId, matchId)}/boards`, {
        method: 'POST',
        body: payload,
      })
      return res.data
    } catch (err) {
      notification.error(t('match.turn.error.create_board_failed'))
      throw err
    }
  }

  /** 団体戦の子ボード一覧を取得する（親 ID スコープ・board_number 昇順）。 */
  async function listBoards(orgId: number, matchId: string): Promise<TurnMatchResponse[]> {
    try {
      const res = await api<{ data: TurnMatchResponse[] }>(`${base(orgId, matchId)}/boards`)
      return res.data ?? []
    } catch (err) {
      notification.error(t('match.turn.error.load_boards_failed'))
      throw err
    }
  }

  // ─── 局面写真（presign 方式・01 §B.7） ───

  /** 局面写真の presign URL を発行する（BE が SVG 除外・サイズ上限を検証）。 */
  async function presignAttachment(
    orgId: number,
    matchId: string,
    payload: MatchAttachmentPresignRequestPayload,
  ): Promise<MatchAttachmentPresignResponsePayload> {
    const res = await api<{ data: MatchAttachmentPresignResponsePayload }>(
      `${base(orgId, matchId)}/attachments/presign`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  /**
   * presign で得た uploadUrl へストレージへ直接 PUT する（credentials なし・Content-Type 一致）。
   * useBulletinAttachments.uploadToR2 と同じ方式 A。
   */
  async function uploadToStorage(uploadUrl: string, file: File): Promise<void> {
    await ofetch(uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type },
    })
  }

  /** 局面写真メタデータを確定する（PUT 完了後に呼ぶ）。 */
  async function confirmAttachment(
    orgId: number,
    matchId: string,
    payload: MatchAttachmentConfirmRequestPayload,
  ): Promise<MatchAttachmentResponsePayload> {
    const res = await api<{ data: MatchAttachmentResponsePayload }>(
      `${base(orgId, matchId)}/attachments`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  /** 局面写真一覧を取得する。 */
  async function listAttachments(
    orgId: number,
    matchId: string,
  ): Promise<MatchAttachmentResponsePayload[]> {
    try {
      const res = await api<{ data: MatchAttachmentResponsePayload[] }>(
        `${base(orgId, matchId)}/attachments`,
      )
      return res.data ?? []
    } catch (err) {
      notification.error(t('match.turn.error.load_attachments_failed'))
      throw err
    }
  }

  /** 局面写真の短命ダウンロード URL を発行する（生 key は返さない）。 */
  async function attachmentDownloadUrl(
    orgId: number,
    matchId: string,
    attachmentId: string,
  ): Promise<MatchAttachmentDownloadResponsePayload> {
    const res = await api<{ data: MatchAttachmentDownloadResponsePayload }>(
      `${base(orgId, matchId)}/attachments/${attachmentId}/download-url`,
    )
    return res.data
  }

  /** 局面写真を削除する。 */
  async function deleteAttachment(
    orgId: number,
    matchId: string,
    attachmentId: string,
  ): Promise<void> {
    try {
      await api(`${base(orgId, matchId)}/attachments/${attachmentId}`, { method: 'DELETE' })
    } catch (err) {
      notification.error(t('match.turn.error.delete_attachment_failed'))
      throw err
    }
  }

  /**
   * 局面写真を presign → ストレージ PUT → confirm の 3 段でアップロードする。
   * SVG / 上限超過は FE でも事前に弾く（BE も弾くが摩擦を減らす）。
   * @returns 確定済み添付（id を含む）と短命表示 URL
   */
  async function uploadPositionPhoto(
    orgId: number,
    matchId: string,
    file: File,
  ): Promise<{ attachment: MatchAttachmentResponsePayload; displayUrl: string }> {
    if (!isUploadableImage(file)) {
      notification.error(t('match.turn.error.attachment_type_invalid'))
      throw new Error('match attachment: SVG/non-image not allowed')
    }
    if (file.size > MATCH_ATTACHMENT_MAX_BYTES) {
      notification.error(t('match.turn.error.attachment_too_large'))
      throw new Error('match attachment: file too large')
    }
    try {
      // (1) presign
      const presigned = await presignAttachment(orgId, matchId, {
        contentType: file.type,
        fileSize: file.size,
      })
      // (2) ストレージ直 PUT（Content-Type 一致）
      await uploadToStorage(presigned.uploadUrl, file)
      // (3) 確定
      const attachment = await confirmAttachment(orgId, matchId, {
        fileKey: presigned.fileKey,
        originalFilename: file.name,
        contentType: file.type,
        fileSize: file.size,
      })
      // (4) 表示用の短命 URL を発行（生 key は返らない方針のため別取得）
      const dl = await attachmentDownloadUrl(orgId, matchId, attachment.id)
      return { attachment, displayUrl: dl.downloadUrl }
    } catch (err) {
      // 個別関数で通知済みでない経路（PUT 失敗）はここで通知
      notification.error(t('match.turn.error.upload_photo_failed'))
      throw err
    }
  }

  return {
    recordResult,
    createBoard,
    listBoards,
    presignAttachment,
    uploadToStorage,
    confirmAttachment,
    listAttachments,
    attachmentDownloadUrl,
    deleteAttachment,
    uploadPositionPhoto,
  }
}
