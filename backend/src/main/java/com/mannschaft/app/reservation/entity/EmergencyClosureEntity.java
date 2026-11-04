package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 臨時休業エンティティ。一括通知の送信履歴を管理する。
 *
 * <p>部分時間帯休業の表現:
 * <ul>
 *   <li>{@code startTime} と {@code endTime} がいずれも NULL → 終日休業</li>
 *   <li>両方セット → 指定時間帯のみの休業（例: 9:00〜11:00）。{@code startDate}〜{@code endDate} の各日に同じ時間帯が適用される</li>
 * </ul>
 *
 * <p>冪等性（CMP-261104-0334）: {@code Idempotency-Key} ヘッダ付きの送信は {@code idempotencyKey} と
 * {@code requestHash} を保存し、{@code (team_id, created_by, idempotency_key)} の一意制約で同じキーの二重作成を
 * DB で止める。制約名 {@link #IDEMPOTENCY_UNIQUE_CONSTRAINT} は重複判別に使うため Flyway V257 と一致させる
 * （test プロファイルは本 Entity から DDL を作るため、ここにも同じ制約を宣言する）。</p>
 */
@Entity
@Table(name = "emergency_closures",
        uniqueConstraints = @UniqueConstraint(
                name = EmergencyClosureEntity.IDEMPOTENCY_UNIQUE_CONSTRAINT,
                columnNames = {"team_id", "created_by", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class EmergencyClosureEntity extends BaseEntity {

    /** 冪等キーの一意制約名（Flyway V257 と一致。重複判別に使うため変更禁止）。 */
    public static final String IDEMPOTENCY_UNIQUE_CONSTRAINT = "uq_emergency_closures_team_creator_idempotency";

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** 部分時間帯休業の開始時刻。NULLなら終日休業 */
    @Column
    private LocalTime startTime;

    /** 部分時間帯休業の終了時刻。NULLなら終日休業 */
    @Column
    private LocalTime endTime;

    /** 休業理由（先生体調不良 など） */
    @Column(nullable = false, length = 200)
    private String reason;

    /** メール件名 */
    @Column(nullable = false, length = 200)
    private String subject;

    /** メール本文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String messageBody;

    /** 送信件数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer sentCount = 0;

    /** 対象予約を自動キャンセルしたか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean cancelReservations = false;

    @Column(nullable = false)
    private Long createdBy;

    /** 送信の冪等キー（Idempotency-Key ヘッダの UUID・小文字正規化済み）。ヘッダ無しの送信は NULL */
    @Column(columnDefinition = "CHAR(36)")
    private String idempotencyKey;

    /** 正規化した要求内容の SHA-256（16進64文字）。ヘッダ無しの送信は NULL */
    @Column(columnDefinition = "CHAR(64)")
    private String requestHash;
}
