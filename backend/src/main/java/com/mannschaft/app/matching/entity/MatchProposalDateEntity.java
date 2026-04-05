package com.mannschaft.app.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 応募時の日程候補エンティティ。
 */
@Entity
@Table(name = "match_proposal_dates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MatchProposalDateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long proposalId;

    @Column(nullable = false)
    private LocalDate proposedDate;

    private LocalTime proposedTimeFrom;

    private LocalTime proposedTimeTo;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSelected = false;

    /**
     * この日程を選択済みにする。
     */
    public void select() {
        this.isSelected = true;
    }
}
