package com.mannschaft.app.template;

import com.mannschaft.app.common.BaseEntity;
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
 * モジュール定義エンティティ。デフォルト機能・選択式モジュールの定義を管理する。
 */
@Entity
@Table(name = "module_definitions")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ModuleDefinitionEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String slug;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModuleType moduleType;

    @Column(nullable = false)
    private Integer moduleNumber;

    @Column(nullable = false)
    private Boolean requiresPaidPlan;

    @Column(length = 50)
    private String featureFlag;

    private Integer trialDays;

    @Column(nullable = false)
    private Boolean isActive;

    private LocalDateTime deletedAt;

    /**
     * モジュール種別
     */
    public enum ModuleType {
        /** デフォルト機能（常時有効） */
        DEFAULT,
        /** 選択式モジュール（カタログから選択） */
        OPTIONAL
    }

    /**
     * モジュールを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
