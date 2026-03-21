package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.tournament.ParticipantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ディビジョンへの参加チームエンティティ。
 */
@Entity
@Table(name = "tournament_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long divisionId;

    @Column(nullable = false)
    private Long teamId;

    private Integer seed;

    @Column(length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ParticipantStatus status = ParticipantStatus.REGISTERED;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime joinedAt = LocalDateTime.now();

    /**
     * 参加情報を更新する。
     */
    public void update(Integer seed, String displayName) {
        this.seed = seed;
        this.displayName = displayName;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(ParticipantStatus newStatus) {
        this.status = newStatus;
    }
}
