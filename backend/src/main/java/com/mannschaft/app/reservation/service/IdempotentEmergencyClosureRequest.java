package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;

/**
 * 冪等キーと要求ハッシュを載せた臨時休業通知リクエスト（CMP-261104-0334・サービス層内部専用）。
 *
 * <p>{@link EmergencyClosureSendService} が検証・正規化済みのキーと {@link EmergencyClosureRequestHasher} の
 * ハッシュを付けて {@link EmergencyClosureService#sendClosure} へ渡すためだけに使う。HTTP の入力
 * （JSON 逆直列化）には現れない（コンストラクタはパッケージ内限定）。
 * 送信本体のシグネチャを変えないのは、同メソッドが既存の D-3T 凍結キー（推移的クロスドメイン TX の既存負債）の
 * 対象であり、引数を足すと既存負債が新規違反として再計上されるため。</p>
 */
final class IdempotentEmergencyClosureRequest extends CreateEmergencyClosureRequest {

    private final String idempotencyKey;
    private final String requestHash;

    IdempotentEmergencyClosureRequest(CreateEmergencyClosureRequest source, String idempotencyKey,
                                      String requestHash) {
        setStartDate(source.getStartDate());
        setEndDate(source.getEndDate());
        setStartTime(source.getStartTime());
        setEndTime(source.getEndTime());
        setReason(source.getReason());
        setSubject(source.getSubject());
        setMessageBody(source.getMessageBody());
        setCancelReservations(source.isCancelReservations());
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    /** 正規化済み（UUID 小文字）の冪等キー。 */
    String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** 正規化した要求内容の SHA-256（16進64文字）。 */
    String getRequestHash() {
        return requestHash;
    }
}
