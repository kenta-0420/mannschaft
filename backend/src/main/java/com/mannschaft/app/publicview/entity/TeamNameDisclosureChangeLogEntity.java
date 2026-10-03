package com.mannschaft.app.publicview.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * チームの投稿者識別モード変更履歴エンティティ。
 *
 * <p>F19.1 Phase 2: ADMIN が {@code teams.supporter_name_disclosure} を変更した際の
 * 監査ログを保持する。非対称切替ルール検証および UI での「過去 1 年の切替履歴表示」に使用する。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.3</p>
 */
@Entity
@Table(name = "team_name_disclosure_change_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class TeamNameDisclosureChangeLogEntity extends UuidV7Entity {

    /** 変更対象のチーム ID。FK 制約なし（クロスドメイン FK 禁止原則に従う）。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 変更を行ったユーザー ID。FK 制約なし（クロスドメイン FK 禁止原則に従う）。 */
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    /** 変更前の表示モード。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "old_mode", nullable = false)
    private NameDisclosureMode oldMode;

    /** 変更後の表示モード。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "new_mode", nullable = false)
    private NameDisclosureMode newMode;

    /**
     * 確認チェックが完了済みかどうか。
     * {@code confirmed: true} を受け取った場合のみ {@code true} になる。
     * DISPLAY_NAME → REAL_NAME の切替には必須（REAL_NAME → DISPLAY_NAME は任意）。
     */
    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    /** 変更日時。 */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
