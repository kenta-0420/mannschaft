package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.RecallSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;

/** native writer が開始時の本文と設問を凍結する。同じセッションを再構成しない。 */
@Entity
@Table(name="reflection_recall_sessions")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@SuperBuilder
public class RecallSessionEntity extends UuidV7Entity {
    @Column(name="user_id",nullable=false,columnDefinition="BIGINT UNSIGNED") private Long userId;
    @Column(name="entry_id_type",nullable=false,length=8) private String entryIdType;
    @Column(name="entry_source_id",nullable=false,length=80) private String entrySourceId;
    @Column(name="reward_week") private LocalDate rewardWeek;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=20) private RecallSessionStatus status;
    @Column(name="prompt_snapshot",nullable=false,columnDefinition="JSON") private String promptSnapshot;
    @Column(name="original_snapshot",nullable=false,columnDefinition="JSON") private String originalSnapshot;
    @Column(name="answers_json",nullable=false,columnDefinition="JSON") private String answersJson;
    @Enumerated(EnumType.STRING) @Column(name="self_rating",length=20) private RecallSelfRating selfRating;
    @Column(name="started_at",nullable=false) private Instant startedAt;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @Column(name="version",nullable=false) @org.hibernate.annotations.ColumnDefault("0") private long version;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    /** 版の照合と current row lock は writer が先に行う。 */
    public void saveAnswers(String encodedAnswers, Instant at) {
        requireStarted();
        if(encodedAnswers==null || at==null) throw invalid();
        answersJson=encodedAnswers;version=Math.incrementExact(version);updatedAt=at;
    }

    /** attempt 保存と同じ native TX で一度だけ完了する。 */
    public void complete(String encodedAnswers, RecallSelfRating rating, LocalDate week, Instant at) {
        requireStarted();
        if(encodedAnswers==null || rating==null || week==null || at==null) throw invalid();
        answersJson=encodedAnswers;selfRating=rating;rewardWeek=week;completedAt=at;
        status=RecallSessionStatus.COMPLETED;version=Math.incrementExact(version);updatedAt=at;
    }

    /** 取消時に既存 attempt や報酬を生成しない。 */
    public void cancel(Instant at) {
        requireStarted();if(at==null) throw invalid();
        status=RecallSessionStatus.CANCELLED;cancelledAt=at;version=Math.incrementExact(version);updatedAt=at;
    }

    private void requireStarted(){if(status!=RecallSessionStatus.STARTED) throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("想起セッションの遷移が不正です");}
}
