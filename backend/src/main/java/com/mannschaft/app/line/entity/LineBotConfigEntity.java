package com.mannschaft.app.line.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.line.ScopeType;
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
 * LINE BOT設定エンティティ。
 */
@Entity
@Table(name = "line_bot_configs")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class LineBotConfigEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String channelId;

    @Column(nullable = false)
    private byte[] channelSecretEnc;

    @Column(nullable = false)
    private byte[] channelAccessTokenEnc;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(nullable = false, length = 64)
    private String webhookSecret;

    @Column(length = 50)
    private String botUserId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notificationEnabled = true;

    @Column(nullable = false)
    private Long configuredBy;

    private LocalDateTime deletedAt;

    /**
     * BOT設定を更新する。
     */
    public void update(String channelId, byte[] channelSecretEnc, byte[] channelAccessTokenEnc,
                       String webhookSecret, String botUserId, Boolean notificationEnabled) {
        this.channelId = channelId;
        this.channelSecretEnc = channelSecretEnc;
        this.channelAccessTokenEnc = channelAccessTokenEnc;
        this.webhookSecret = webhookSecret;
        this.botUserId = botUserId;
        this.notificationEnabled = notificationEnabled;
    }

    /**
     * 有効/無効を切り替える。
     */
    public void toggleActive() {
        this.isActive = !this.isActive;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
