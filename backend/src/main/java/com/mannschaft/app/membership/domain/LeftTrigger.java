package com.mannschaft.app.membership.domain;

/**
 * 退会の主体（永続化・読取り層）。
 *
 * <p>{@code memberships.left_trigger} の写像。退会が「誰の判断で・自動か人か」を区別する
 * ために独立した列として持つ（{@link LeaveReason} は「なぜ」を表す直交する軸）。</p>
 *
 * <p>{@link #LEGACY} は業務コードから書けない。M-1 migration のバックフィルだけが書き手であり、
 * 業務コードは {@link LeftTriggerCommand}（{@code LEGACY} を含まない 4 値）しか扱えない
 * （§5.2.2.2）。ArchUnit で業務コードからの {@link #LEGACY} 参照を禁止する（AC-703b）。</p>
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §5.2.2 / §5.2.2.1 / §5.2.2.2 / §9.7.5</p>
 */
public enum LeftTrigger {

    /** 本人の自主退会。 */
    SELF,

    /** 理事等による手動での退会・除名。 */
    MANUAL,

    /** システムによる自動処理（purge 等）。 */
    SYSTEM,

    /** アーカイブ期限到来による自動退会。 */
    AUTO_ARCHIVE_EXPIRY,

    /**
     * M-1 migration 適用前に行われた退会。主体は記録されていない
     * （バックフィルによって付与された値であり、業務コードから新規に書く経路は存在しない）。
     */
    LEGACY
}
