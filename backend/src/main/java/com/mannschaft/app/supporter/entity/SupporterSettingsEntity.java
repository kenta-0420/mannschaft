package com.mannschaft.app.supporter.entity;

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

/**
 * サポーター設定エンティティ。チーム・組織ごとの自動承認設定を管理する。
 * レコードが存在しない場合は autoApprove=true（即時承認）をデフォルトとする。
 */
@Entity
@Table(name = "supporter_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SupporterSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スコープ種別: TEAM または ORGANIZATION */
    @Column(nullable = false, length = 20)
    private String scopeType;

    /** スコープID: チームID または 組織ID */
    @Column(nullable = false)
    private Long scopeId;

    /** 自動承認フラグ: true=申請を即時承認、false=管理者手動承認 */
    @Column(nullable = false)
    private boolean autoApprove;

    /**
     * 自動承認設定を更新する。
     */
    public void updateAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }
}
