package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
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
 * F03.11 募集型予約: 固定大カテゴリマスタ。
 * システム提供のカテゴリで、ユーザー側からは作成不可。
 */
@Entity
@Table(name = "recruitment_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentCategoryEntity extends BaseEntity {

    @Column(nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "name_i18n_key", nullable = false, length = 100)
    private String nameI18nKey;

    @Column(length = 50)
    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentParticipationType defaultParticipationType;

    @Column(nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean isActive;
}
