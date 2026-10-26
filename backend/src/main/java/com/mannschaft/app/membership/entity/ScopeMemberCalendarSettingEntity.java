package com.mannschaft.app.membership.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * スコープ別のメンバー表示設定。
 *
 * <p>membership 履歴とは分離し、退会・再加入で色を維持する。userId/scopeId は
 * クロスドメイン参照のため DB FK を持たない。</p>
 */
@Entity
@Table(name = "scope_member_calendar_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScopeMemberCalendarSettingEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 12)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "calendar_color", nullable = false, length = 7)
    private String calendarColor;

    public void updateCalendarColor(String calendarColor) {
        this.calendarColor = calendarColor;
    }
}
