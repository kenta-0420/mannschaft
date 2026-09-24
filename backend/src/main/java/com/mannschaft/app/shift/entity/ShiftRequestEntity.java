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
                columnNames = {"schedule_id", "user_id", "slot_id_uq", "slot_date_uq"}))
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

    /**
     * 一意性用の正規化日付（生成列。設計 §11.5.1.2・Codex 検分 P1 の是正）。
     *
     * <p>枠指定の希望（{@code slot_id} 非 null）の一意性は <b>{@code (scheduleId, userId, slotId)}</b> であり、
     * <b>{@code slotDate} を含めてはならない</b>。含めると「同じ枠なのに日付だけ違う」重複行が残せてしまい、
     * {@code findByScheduleIdAndUserIdAndSlotId}（{@code Optional} 戻り）が複数行を掴んで 500 になる。
     * そうした行は実際に作られうる:</p>
     *
     * <ul>
     *   <li>移行前の既存行は {@code slotId} / {@code slotDate} の整合検証を一度も通っていない。</li>
     *   <li>{@code ShiftSlotService#updateSlot} は枠の {@code slotDate} を書き換えるが
     *       {@code shift_requests} には触れないため、枠の日付を後から変えると希望側が取り残される
     *       （射程外の既存欠陥として起票済み）。</li>
     * </ul>
     *
     * <p>一方、日単位の希望（{@code slot_id} が null）は <b>{@code slotDate} で一意</b>でなければならない
     *（AC-8-03）。この<b>異なる 2 つの一意単位を 1 本の UNIQUE で表す</b>ために、
     * 枠指定のときだけ日付を番兵 {@code 1000-01-01} へ潰す生成列を噛ませる。</p>
     *
     * <p>VIRTUAL である理由と、Flyway DDL と一字一句同じであるべき理由は {@link #slotIdUq} と同じ。</p>
     */
    @Column(name = "slot_date_uq", insertable = false, updatable = false,
            columnDefinition = "DATE AS (CASE WHEN slot_id IS NULL THEN slot_date ELSE DATE '1000-01-01' END) "
                    + "VIRTUAL NOT NULL")
    private LocalDate slotDateUq;

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
