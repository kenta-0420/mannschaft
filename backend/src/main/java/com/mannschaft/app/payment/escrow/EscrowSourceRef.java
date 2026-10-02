package com.mannschaft.app.payment.escrow;

/**
 * 引き当ての三つ組（{@code source_kind} × {@code source_id} × {@code source_participant_id}）のうち、
 * 種別を除いた識別子の対。一括判定 API の入出力キーとして用いる。
 *
 * <p>{@link ConnectChargeService#filterPayeeSettlementManaged} は「どの三つ組が操作者に許されたか」を
 * この型の集合で返す。呼び出し側（他ドメイン）は escrow の実体を一切受け取らず、
 * <b>自分が渡した識別子のどれが許されたか</b>だけを知る（payment ドメインの内部構造を漏らさない）。</p>
 *
 * <p>{@code sourceParticipantId} は {@code null} を取りうる（参加者に紐づかない引き当て）。
 * {@code record} の {@code equals}/{@code hashCode} が null を含めて正しく働くため、
 * そのまま {@code Set} のキーにしてよい。</p>
 *
 * @param sourceId            引き当て元の ID（募集 ID 等）
 * @param sourceParticipantId 引き当て元の参加者 ID（無い場合は {@code null}）
 */
public record EscrowSourceRef(Long sourceId, Long sourceParticipantId) {
}
