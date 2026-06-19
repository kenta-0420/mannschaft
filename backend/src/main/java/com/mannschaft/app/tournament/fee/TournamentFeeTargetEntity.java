package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 大会参加費の対象チーム明細エンティティ（F08.7.1/07 §2）。
 *
 * <p>{@code target_scope = SPECIFIC_TEAMS} のとき、どのチームが対象かを表す。
 * 親 {@link TournamentFeeEntity} と同一ドメインのため CASCADE 削除を許可する（原則 2）。
 * {@code teamId} は team ドメインへの ID 参照のみ（クロスドメイン FK なし／原則 1）。</p>
 */
@Entity
@Table(name = "tournament_fee_target")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TournamentFeeTargetEntity extends UuidV7Entity {

    /** 親 tournament_fee.id（同一ドメイン） */
    @Column(nullable = false)
    private UUID feeId;

    /** 対象チーム（teams.id への ID 参照・FK なし／原則1） */
    @Column(nullable = false)
    private Long teamId;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
