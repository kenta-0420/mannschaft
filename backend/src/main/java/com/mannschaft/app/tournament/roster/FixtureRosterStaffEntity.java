package com.mannschaft.app.tournament.roster;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ベンチ入り役員エンティティ（F08.7.1/05 §8.3）。
 *
 * <p>監督・コーチ・トレーナー等、選手以外のベンチ入り役員を試合単位×参加チーム単位で記載する。
 * アプリ未登録者（協会派遣の帯同審判等）も記載できるよう {@code userId} は NULL 可。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則6・{@link UuidV7Entity} 継承）。</li>
 *   <li>{@code matchId} / {@code participantId} は同一 tournament ドメイン内（BIGINT PK）への ID 参照。</li>
 *   <li>{@code userId} は user ドメインへの ID 参照。いずれもクロスドメイン FK なし（原則1）。</li>
 * </ul>
 */
@Entity
@Table(name = "match_roster_staff")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class FixtureRosterStaffEntity extends UuidV7Entity {

    /** 対象試合（tournament_matches.id への ID 参照・同一ドメイン） */
    @Column(nullable = false)
    private Long matchId;

    /** 参加チーム（tournament_participants.id への ID 参照・自チーム分の roster と同じ単位） */
    @Column(nullable = false)
    private Long participantId;

    /** 役職（監督/コーチ/トレーナー/帯同審判 等。アプリ層で許容値検証） */
    @Column(nullable = false, length = 32)
    private String role;

    /** 氏名（アプリ未登録者も記載可のため文字列で保持） */
    @Column(nullable = false, length = 128)
    private String name;

    /** 紐付くユーザー（user ドメインへの ID 参照・NULL 可） */
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
