package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** 公開済みcare規則の不変版。次UTC週以降の公開を管理writerで検証する。 */
@Entity
@Table(name = "ranch_care_rules")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchCareRuleEntity extends RanchEntity {
    @Column(name = "version_number", nullable = false)
    private long versionNumber;
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;
    @Column(name = "amount_xp", nullable = false)
    private long amountXp;
    @Column(name = "weekly_cap_xp", nullable = false)
    private long weeklyCapXp;
    @Column(name = "juvenile_xp", nullable = false)
    private long juvenileXp;
    @Column(name = "adult_xp", nullable = false)
    private long adultXp;
    @Column(name = "content_hash", nullable = false, length = 32)
    private byte[] contentHash;
    @Column(name = "published_by", nullable = false)
    private Long publishedBy;
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;
}