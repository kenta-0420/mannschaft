package com.mannschaft.app.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * マッチング成立後の相互レビューエンティティ。
 */
@Entity
@Table(name = "match_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MatchReviewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long proposalId;

    @Column(nullable = false)
    private Long reviewerTeamId;

    @Column(nullable = false)
    private Long revieweeTeamId;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Short rating;

    @Column(length = 1000)
    private String comment;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPublic = true;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
