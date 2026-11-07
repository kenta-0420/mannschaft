package com.mannschaft.app.digest.event;

/**
 * AI ダイジェスト生成の完了／失敗通知の配送要求（Issue #2997 / CMP-260827-1152 G7-a）。
 *
 * <p>{@code DigestGenerationRunner}（{@code REQUIRES_NEW}）が GENERATED／FAILED への遷移を確定するトランザクションの内側で
 * publish し、{@link DigestGenerationNotificationListener}（{@code AFTER_COMMIT} + {@code @Async("event-pool")}）が
 * commit 後に作成者へ配送する。確定TXが rollback すれば通知は 1 件も作られない。</p>
 *
 * <p>描画済み文字列や {@code LocalDateTime} は載せず、ID と種別のみを運ぶ（文面は受信者の locale で
 * リスナーが組み立てる）。</p>
 *
 * @param digestId    対象ダイジェストID
 * @param kind        完了か失敗か
 * @param triggeredBy 通知の宛先（生成を起動したユーザー。NULL は配送対象なし）
 * @param scopeType   スコープ種別（{@code DigestScopeType} の enum 名）
 * @param scopeId     スコープID
 */
public record DigestGenerationNotificationEvent(
        Long digestId,
        Kind kind,
        Long triggeredBy,
        String scopeType,
        Long scopeId) {

    /** 通知の種別。 */
    public enum Kind {
        /** 生成完了（DIGEST_COMPLETED）。 */
        COMPLETED,
        /** 生成失敗（DIGEST_FAILED）。 */
        FAILED
    }
}
