package com.mannschaft.app.shift.entity;

import com.mannschaft.app.shift.ShiftPreference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフト希望エンティティ。メンバーのシフト希望を管理する。
 */
@Entity
@Table(
        name = "shift_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sr_schedule_user_slot",
                columnNames = {"schedule_id", "user_id", "slot_id_uq", "slot_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long userId;

    private Long slotId;

    /**
     * 一意性用の正規化列（生成列。設計 §11.5.1.2）。
     *
     * <p>MySQL の UNIQUE は NULL を「互いに異なる値」として扱うため、{@code slot_id} が
     * {@code NULL}（日単位希望）の行は単純な UNIQUE では重複を防げない。{@code COALESCE(slot_id, 0)}
     * の生成列を噛ませ、{@code (schedule_id, user_id, slot_id_uq, slot_date)} に UNIQUE を張る。</p>
     *
     * <p><b>VIRTUAL である理由（実測）</b>: {@code slot_id} は FK {@code fk_sr_slot} のベースカラムであり、
     * MySQL 8.0 では STORED 生成カラムを載せられない（{@code ALTER TABLE} が
     * {@code ERROR 1215: Cannot add foreign key constraint} で失敗することを実機の MySQL 8.0 で確認済み。
     * 同型の事故が {@code V11.030} → PR #3188 で起きている）。VIRTUAL であれば FK と併存でき、
     * インデックス（UNIQUE を含む）も張れる。</p>
     *
     * <p><b>Flyway DDL と一字一句同じ定義であること</b>。統合テストは {@code ddl-auto: create} ＋
     * {@code flyway.enabled: false} で走るため、ここが欠けると本番だけ制約が無い（あるいはその逆）状態になる。
     * 対応する移行は {@code V208.*__add_shift_requests_slot_uniqueness.sql}。</p>
     */
    @Column(name = "slot_id_uq", insertable = false, updatable = false,
            columnDefinition = "BIGINT UNSIGNED AS (COALESCE(slot_id, 0)) VIRTUAL NOT NULL")
    private Long slotIdUq;

    @Column(nullable = false)
    private LocalDate slotDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShiftPreference preference;

    @Column(length = 200)
    private String note;

    @Column(name = "is_proxy_input", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean isProxyInput = false;

    @Column(name = "proxy_input_record_id")
    private Long proxyInputRecordId;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.submittedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 希望を更新する。
     *
     * @param preference 新しい希望種別
     * @param note       備考
     */
    public void updatePreference(ShiftPreference preference, String note) {
        this.preference = preference;
        this.note = note;
    }
}
