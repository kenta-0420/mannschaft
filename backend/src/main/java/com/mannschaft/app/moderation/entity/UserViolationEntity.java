package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.moderation.ViolationType;
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

import java.time.LocalDateTime;

/**
 * ユーザー違反累積エンティティ。違反記録・時効管理・ヤバいやつ判定に使用する。
 */
@Entity
@Table(name = "user_violations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserViolationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false)
    private Long actionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ViolationType violationType;

    @Column(nullable = false, length = 30)
    private String reason;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 違反を無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }
}
