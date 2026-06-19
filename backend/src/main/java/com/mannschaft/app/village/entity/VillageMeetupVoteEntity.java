package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageMeetupVoteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 寄合投票エンティティ（F17.1 Phase 3-β）。
 *
 * <p>候補日 1 つに対して 1 ユーザー 1 票（UNIQUE 制約あり）。
 * 投票変更は upsert で実現（{@code candidate_date_id, voter_user_id} で検索後 update or insert）。</p>
 */
@Entity
@Table(name = "village_meetup_votes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMeetupVoteEntity extends UuidV7Entity {

    /** FK → village_meetup_candidate_dates.id（同一ドメイン CASCADE） */
    @Column(name = "candidate_date_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID candidateDateId;

    /** 投票者ユーザーID（FK 張らない・原則1） */
    @Column(name = "voter_user_id", nullable = false)
    private Long voterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_type", nullable = false, length = 20)
    private VillageMeetupVoteType voteType;

    @Column(name = "voted_at", nullable = false)
    private LocalDateTime votedAt;

    @PrePersist
    protected void onCreate() {
        if (this.votedAt == null) {
            this.votedAt = LocalDateTime.now();
        }
    }
}
