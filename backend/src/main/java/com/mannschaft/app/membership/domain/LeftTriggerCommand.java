package com.mannschaft.app.membership.domain;

/**
 * 退会の主体（業務コマンド層）。
 *
 * <p>{@link LeftTrigger} から {@code LEGACY} を除いた 4 値のみを持つ。業務コード
 * （{@code MembershipLeaveRequest} 等）が扱えるのはこの型だけであり、{@code LEGACY} を
 * 指名する呼び出しをそもそも型として書けなくする（§5.2.2.2）。</p>
 *
 * <p>永続化時に {@link LeftTrigger} へ変換する（4 値は 1 対 1 対応）。</p>
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §5.2.2.2</p>
 */
public enum LeftTriggerCommand {

    /** 本人の自主退会。 */
    SELF,

    /** 理事等による手動での退会・除名。 */
    MANUAL,

    /** システムによる自動処理（purge 等）。 */
    SYSTEM,

    /** アーカイブ期限到来による自動退会。 */
    AUTO_ARCHIVE_EXPIRY;

    /**
     * 永続化・読取り層の {@link LeftTrigger} へ変換する。
     */
    public LeftTrigger toLeftTrigger() {
        return LeftTrigger.valueOf(this.name());
    }
}
