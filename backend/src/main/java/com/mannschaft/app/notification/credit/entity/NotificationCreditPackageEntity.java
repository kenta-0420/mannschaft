package com.mannschaft.app.notification.credit.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 通知プリペイドクレジットパッケージエンティティ。
 *
 * <p>マスタ例外: 全組織共通の固定価格帯。シャーディング時は全シャードにコピーされる
 * 参照データのため BIGINT AUTO_INCREMENT を使用（UUIDv7原則の例外）。</p>
 */
@Entity
@Table(name = "notification_credit_packages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class NotificationCreditPackageEntity extends BaseEntity {

    /** パッケージ名（例: スタンダード 10万通） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 付与通数 */
    @Column(nullable = false)
    private Long credits;

    /** 日本円価格 */
    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal priceJpy;

    /**
     * Stripe Price ID。初回購入時に遅延生成される。
     * NULL の場合は購入フロー開始時に Stripe へ登録する。
     */
    @Column(length = 200)
    private String stripePriceId;

    /** 販売中フラグ */
    @Column(nullable = false)
    @SuperBuilder.Default
    private Boolean isActive = true;

    /** UI 表示順 */
    @Column(nullable = false)
    @SuperBuilder.Default
    private Integer displayOrder = 0;

    /**
     * Stripe Price ID を設定する（遅延生成時に呼ぶ）。
     */
    public void setStripePriceId(String stripePriceId) {
        this.stripePriceId = stripePriceId;
    }
}
