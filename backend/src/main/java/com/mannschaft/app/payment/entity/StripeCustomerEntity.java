package com.mannschaft.app.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stripe 顧客エンティティ。ユーザーと Stripe Customer ID の1対1マッピングを管理する。
 */
@Entity
@Table(name = "stripe_customers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class StripeCustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String stripeCustomerId;

    /**
     * F08.9 P5 第二波: off_session 既定の Stripe PaymentMethod ID（{@code pm_xxx}）。
     *
     * <p>SetupIntent で confirm 済みの PM を attach＋default 設定したときに焼き付ける。
     * 継続課金（subscribe・案b）が次サイクル以降の off_session 課金で再利用する。
     * {@code null}＝未保存（subscribe 時に未保存なら 409 で拒否し SetupIntent 導線へ誘導）。</p>
     */
    @Column(name = "default_payment_method", length = 64)
    private String defaultPaymentMethod;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 既定の PaymentMethod を設定する（SetupIntent confirm 後の attach＋default 焼付）。
     *
     * @param paymentMethodId Stripe PaymentMethod ID（{@code pm_xxx}）
     */
    public void setDefaultPaymentMethod(String paymentMethodId) {
        this.defaultPaymentMethod = paymentMethodId;
    }
}
