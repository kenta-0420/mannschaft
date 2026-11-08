package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.ShiftPreference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * メンバーデフォルト勤務可能時間エンティティ。曜日ごとの勤務可能デフォルトを管理する。
 */
@Entity
/*
 * 一意キーは本番 DDL（V3.075 の uq_mad_user_team_dow_time）と同じものをエンティティにも宣言する
 * （CMP-261010-1432 欠陥 H）。テスト環境は ddl-auto で表を作るため、宣言が無いと一意キーと
 * その左端 (user_id, team_id) の索引が無い表になり、並行保存の直列化（FOR UPDATE の範囲ロック）と
 * 一意キー違反の契約を本番と同じ条件で検証できない。
 */
@Table(name = "member_availability_defaults", uniqueConstraints = @UniqueConstraint(
        name = "uq_mad_user_team_dow_time",
        columnNames = {"user_id", "team_id", "day_of_week", "start_time", "end_time"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MemberAvailabilityDefaultEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShiftPreference preference;

    @Column(length = 200)
    private String note;

    /**
     * 勤務可能設定を更新する。
     *
     * @param preference 希望種別
     * @param note       備考
     */
    public void updateAvailability(ShiftPreference preference, String note) {
        this.preference = preference;
        this.note = note;
    }
}
