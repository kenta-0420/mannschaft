package com.mannschaft.app.line.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.line.LineMessageType;
import com.mannschaft.app.line.MessageDirection;
import com.mannschaft.app.line.MessageStatus;
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
 * LINEメッセージログエンティティ。
 */
@Entity
@Table(name = "line_message_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class LineMessageLogEntity extends BaseEntity {

    @Column(nullable = false)
    private Long lineBotConfigId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineMessageType messageType;

    @Column(length = 50)
    private String lineUserId;

    @Column(length = 500)
    private String contentSummary;

    @Column(length = 50)
    private String lineMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MessageStatus status = MessageStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String errorDetail;

    /**
     * ステータスを更新する。
     */
    public void updateStatus(MessageStatus status) {
        this.status = status;
    }

    /**
     * エラー情報を設定する。
     */
    public void markFailed(String errorDetail) {
        this.status = MessageStatus.FAILED;
        this.errorDetail = errorDetail;
    }

    /**
     * 送信完了を設定する。
     */
    public void markSent(String lineMessageId) {
        this.status = MessageStatus.SENT;
        this.lineMessageId = lineMessageId;
    }
}
