package com.mannschaft.app.social.dto;

/**
 * フレンド転送配信範囲を表す列挙。
 *
 * <p>
 * Phase 1 では {@link #MEMBER} のみ受理し、{@link #MEMBER_AND_SUPPORTER} を
 * 指定すると Service 層で 400 Bad Request を返す。GDPR / 個人情報保護の観点から
 * SUPPORTER（保護者等）への波及は Phase 3 で申請・審査メカニズム整備後に解禁する
 * （設計書 §3 §5 §6 参照）。
 * </p>
 */
public enum ForwardTarget {

    /** チーム MEMBER のみ（Phase 1 はこれ固定） */
    MEMBER,

    /** MEMBER + SUPPORTER（Phase 3 以降で解禁予定。Phase 1 では 400 エラー） */
    MEMBER_AND_SUPPORTER
}
