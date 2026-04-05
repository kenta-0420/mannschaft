package com.mannschaft.app.line.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.SnsProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * SNSフィード設定エンティティ。
 */
@Entity
@Table(name = "sns_feed_configs")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SnsFeedConfigEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SnsProvider provider;

    @Column(nullable = false, length = 100)
    private String accountUsername;

    private byte[] accessTokenEnc;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(nullable = false)
    @Builder.Default
    private Short displayCount = 6;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    private Long configuredBy;

    private LocalDateTime deletedAt;

    /**
     * フィード設定を更新する。
     */
    public void update(String accountUsername, byte[] accessTokenEnc,
                       Short displayCount, Boolean isActive) {
        this.accountUsername = accountUsername;
        if (accessTokenEnc != null) {
            this.accessTokenEnc = accessTokenEnc;
        }
        this.displayCount = displayCount;
        this.isActive = isActive;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
