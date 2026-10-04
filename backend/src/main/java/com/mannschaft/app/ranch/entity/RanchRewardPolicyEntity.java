package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** 公開済み報酬政策。公開後の内容と有効週を更新しない。 */
@Entity
@Table(name = "ranch_reward_policies")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchRewardPolicyEntity extends RanchEntity {
    @Column(name = "version_number", nullable = false)
    private long versionNumber;
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(name = "settings_json", nullable = false, columnDefinition = "json")
    private String settingsJson;
    @Column(name = "content_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] contentHash;
    @Column(name = "published_by", nullable = false)
    private Long publishedBy;
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    public byte[] getContentHash() { return contentHash.clone(); }
}
