package com.mannschaft.app.advertising.entity;

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
 * 広告ターゲティングルールエンティティ。
 */
@Entity
@Table(name = "ad_targeting_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdTargetingRuleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long campaignId;

    @Column(length = 20)
    private String targetPrefecture;

    @Column(length = 30)
    private String targetTemplate;
}
