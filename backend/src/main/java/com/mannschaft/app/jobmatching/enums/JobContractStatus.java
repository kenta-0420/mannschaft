package com.mannschaft.app.jobmatching.enums;

/**
 * 求人契約のステータス。
 *
 * <p>MVP（Phase 13.1.1）では {@link #MATCHED} / {@link #COMPLETION_REPORTED} /
 * {@link #COMPLETED} / {@link #CANCELLED} を主に利用する。
 * QR チェックイン／エスクロー／紛争仲裁などの後続 Phase で残りの状態を使用する。</p>
 *
 * <p>状態遷移詳細は F13.1 §5.4 を参照。</p>
 */
public enum JobContractStatus {

    /** 採用確定（PaymentIntent 作成、Worker がチェックイン前） */
    MATCHED,

    /** QR IN 済み（Worker がチェックイン完了） */
    CHECKED_IN,

    /** 業務遂行中（CHECKED_IN からの自動遷移） */
    IN_PROGRESS,

    /** QR OUT 済み（Worker がチェックアウト完了） */
    CHECKED_OUT,

    /** 業務時間確定済み（ORG_CONFIRM 方式: 運営確定 + Worker 承認） */
    TIME_CONFIRMED,

    /** 完了報告済み（Worker が完了報告を送信） */
    COMPLETION_REPORTED,

    /** 完了（旧フラグ、互換維持のため残置。PAID と統合的に「完了扱い」とする） */
    COMPLETED,

    /** 承認済み（Requester が完了承認、エスクロー HOLDING） */
    AUTHORIZED,

    /** キャプチャ済み（Stripe capture 完了） */
    CAPTURED,

    /** 支払完了（Stripe Express への transfer 完了） */
    PAID,

    /** キャンセル（業務中止・合意キャンセル等） */
    CANCELLED,

    /** 紛争中（異議申立発生、仲裁処理中） */
    DISPUTED
}
