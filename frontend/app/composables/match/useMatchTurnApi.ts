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
 * 【生成型への返済（旧 6-④c 前向きユニオン）】
 * これらの BE DTO（MatchRecordTurnResultRequest / MatchRecordBoardCreateRequest /
 * MatchRecordAttachment* / MatchDetailResponse）は OpenAPI 再生成で生成型 `Schemas` へ反映済みと
 * なったため、旧・手書きの前向きユニオン型を撤去し、生成型由来のエイリアスへ置換した
 * （生成型が正本・二重定義を解消・any なし）。
 * 例外: 引分け（winnerSide=null）は OpenAPI enum（"HOME" | "AWAY"）に null が含まれないため、
 * FE が記録 UI で扱う「引分け」を表現するための補助ユニオン `TurnResultWinnerSide` のみ手書きで残す
 * （送出時は生成型の器へ載せ替える＝境界 1 箇所・MATCH_028 整合）。
 *
 * R2 直 PUT は useBulletinAttachments と同じ presign 方式 A（ofetch 直叩き・credentials なし・
 * Content-Type を presign 時と一致）を踏襲する（01 §B.7）。
 */
import { ofetch } from 'ofetch'
import type { components } from '~/types/generated'

type Schemas = components['schemas']

// ===== DTO 型（生成型の再エクスポート・旧前向きユニオンの返済先） =====

/** 対局結果記録リクエスト（生成型 MatchRecordTurnResultRequest）。 */
export type MatchTurnResultRequestPayload = Schemas['MatchRecordTurnResultRequest']
/** 団体戦の子ボード作成リクエスト（生成型 MatchRecordBoardCreateRequest）。 */
export type MatchBoardCreateRequestPayload = Schemas['MatchRecordBoardCreateRequest']
/** ターン制を含む試合詳細レスポンス（生成型 MatchDetailResponse・winMethod/totalMoves/parentMatchId/boardNumber 反映済み）。 */
export type TurnMatchResponse = Schemas['MatchDetailResponse']
/** 局面写真 presign リクエスト（生成型 MatchRecordAttachmentPresignRequest）。 */
export type MatchAttachmentPresignRequestPayload = Schemas['MatchRecordAttachmentPresignRequest']
/** 局面写真 presign レスポンス（生成型 MatchRecordAttachmentPresignResponse）。 */
export type MatchAttachmentPresignResponsePayload = Schemas['MatchRecordAttachmentPresignResponse']
/** 局面写真 確定リクエスト（生成型 MatchRecordAttachmentConfirmRequest）。 */
export type MatchAttachmentConfirmRequestPayload = Schemas['MatchRecordAttachmentConfirmRequest']
/** 局面写真レスポンス（生成型 MatchRecordAttachmentResponse）。 */
export type MatchAttachmentResponsePayload = Schemas['MatchRecordAttachmentResponse']
/** 局面写真 短命 DL URL レスポンス（生成型 MatchRecordAttachmentDownloadResponse）。 */
export type MatchAttachmentDownloadResponsePayload = Schemas['MatchRecordAttachmentDownloadResponse']

/**
 * 勝者サイド（HOME=先手/黒・AWAY=後手/白・null=引分け）。
 * 生成型 MatchRecordTurnResultRequest.winnerSide は enum "HOME" | "AWAY" に null を含まないため、
 * FE が記録 UI で扱う「引分け」を表す補助ユニオンとして手書きで残す（送出時に器へ載せ替える）。
 */
export type TurnResultWinnerSide = NonNullable<MatchTurnResultRequestPayload['winnerSide']> | null

/**
 * 対局結果記録の **ワイヤーペイロード**（送出 JSON 形）。
 * BE は引分けを `winnerSide=null` の明示で受ける（§4.2）。生成型 DTO は enum に null を含まないため、
 * 送出時のみ null 明示を許す形で生成型を拡張する（境界 1 箇所・挙動不変＝従前と同じ JSON を送る）。
 */
export type MatchTurnResultWirePayload = Omit<
  MatchTurnResultRequestPayload,
  'winnerSide' | 'winMethod' | 'totalMoves'
> & {
  winnerSide: TurnResultWinnerSide
  winMethod?: string | null
  totalMoves?: number | null
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
): MatchTurnResultWirePayload {
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
    payload: MatchTurnResultWirePayload,
  ): Promise<TurnMatchResponse> {
    try {
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
  ): Promise<{ attachment: MatchAttachmentResponsePayload & { id: string }; displayUrl: string }> {
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
      // 生成型では uploadUrl/fileKey は optional（OpenAPI）。BE は常に返すが、欠落時は不正応答として弾く
      // （症状を隠さず根治・null を握り潰さない）。
      if (!presigned.uploadUrl || !presigned.fileKey) {
        throw new Error('match attachment: presign response missing uploadUrl/fileKey')
      }
      // (2) ストレージ直 PUT（Content-Type 一致）
      await uploadToStorage(presigned.uploadUrl, file)
      // (3) 確定
      const attachment = await confirmAttachment(orgId, matchId, {
        fileKey: presigned.fileKey,
        originalFilename: file.name,
        contentType: file.type,
        fileSize: file.size,
      })
      // 生成型では id は optional。確定応答に id が無いのは不正応答として弾く。
      const attachmentId = attachment.id
      if (!attachmentId) {
        throw new Error('match attachment: confirm response missing id')
      }
      // (4) 表示用の短命 URL を発行（生 key は返らない方針のため別取得）
      const dl = await attachmentDownloadUrl(orgId, matchId, attachmentId)
      if (!dl.downloadUrl) {
        throw new Error('match attachment: download-url response missing downloadUrl')
      }
      return { attachment: { ...attachment, id: attachmentId }, displayUrl: dl.downloadUrl }
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
