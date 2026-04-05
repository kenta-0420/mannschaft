package com.mannschaft.app.directmail.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメール配信ログエンティティ。送信メールの状態・統計を管理する。
 */
@Entity
@Table(name = "direct_mail_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DirectMailLogEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long senderId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String bodyMarkdown;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(nullable = false, length = 20)
    private String recipientType;

    @Column(columnDefinition = "JSON")
    private String recipientFilter;

    private Integer estimatedRecipients;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalRecipients = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer sentCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer openedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer bouncedCount = 0;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    private LocalDateTime scheduledAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime sentAt;

    /**
     * メール内容を更新する（下書き編集）。
     */
    public void update(String subject, String bodyMarkdown, String bodyHtml,
                       String recipientType, String recipientFilter, Integer estimatedRecipients) {
        this.subject = subject;
        this.bodyMarkdown = bodyMarkdown;
        this.bodyHtml = bodyHtml;
        this.recipientType = recipientType;
        this.recipientFilter = recipientFilter;
        this.estimatedRecipients = estimatedRecipients;
    }

    /**
     * 送信ステータスを即時送信に変更する。
     */
    public void markSending() {
        this.status = "SENDING";
    }

    /**
     * 送信完了にする。
     */
    public void markSent(int totalRecipients, int sentCount) {
        this.status = "SENT";
        this.totalRecipients = totalRecipients;
        this.sentCount = sentCount;
        this.sentAt = LocalDateTime.now();
    }

    /**
     * 送信失敗にする。
     */
    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
    }

    /**
     * 予約送信を設定する。
     */
    public void schedule(LocalDateTime scheduledAt) {
        this.status = "SCHEDULED";
        this.scheduledAt = scheduledAt;
    }

    /**
     * 送信をキャンセルする。
     */
    public void cancel() {
        this.status = "CANCELLED";
    }

    /**
     * 開封数をインクリメントする。
     */
    public void incrementOpenedCount() {
        this.openedCount++;
    }

    /**
     * バウンス数をインクリメントする。
     */
    public void incrementBouncedCount() {
        this.bouncedCount++;
    }
}
