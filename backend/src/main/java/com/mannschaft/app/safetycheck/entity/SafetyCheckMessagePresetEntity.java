package com.mannschaft.app.safetycheck.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 安否確認メッセージプリセットエンティティ。定型回答メッセージを管理する。
 */
@Entity
@Table(name = "safety_check_message_presets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SafetyCheckMessagePresetEntity extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String body;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * プリセットを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * プリセットを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * プリセットの内容を更新する。
     *
     * @param body      メッセージ本文
     * @param sortOrder 表示順
     */
    public void update(String body, Integer sortOrder) {
        this.body = body;
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }
}
