package com.mannschaft.app.safetycheck.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.safetycheck.FollowupStatus;
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

/**
 * 安否確認フォローアップエンティティ。要支援者へのフォローアップ状態を管理する。
 */
@Entity
@Table(name = "safety_response_followups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SafetyResponseFollowupEntity extends BaseEntity {

    @Column(nullable = false)
    private Long safetyResponseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FollowupStatus followupStatus = FollowupStatus.PENDING;

    private Long assignedTo;

    @Column(length = 500)
    private String note;

    /**
     * フォローアップを更新する。
     *
     * @param followupStatus ステータス
     * @param assignedTo     担当者ID
     * @param note           備考
     */
    public void update(FollowupStatus followupStatus, Long assignedTo, String note) {
        if (followupStatus != null) this.followupStatus = followupStatus;
        if (assignedTo != null) this.assignedTo = assignedTo;
        if (note != null) this.note = note;
    }
}
