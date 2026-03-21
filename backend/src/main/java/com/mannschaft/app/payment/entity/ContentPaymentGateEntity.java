package com.mannschaft.app.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * コンテンツ単位のアクセスゲートエンティティ。ポリモーフィック設計で各コンテンツモジュールと連携する。
 */
@Entity
@Table(name = "content_payment_gates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ContentPaymentGateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentItemId;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private Long contentId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isTitleHidden = false;

    private Long createdBy;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
