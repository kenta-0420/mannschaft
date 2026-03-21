package com.mannschaft.app.seal.entity;

import com.mannschaft.app.seal.StampTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 押印ログエンティティ。押印履歴と検証情報を記録する。
 *
 * <p>created_at のみ・updated_at なしのため BaseEntity を継承せず独自に定義する。</p>
 */
@Entity
@Table(name = "seal_stamp_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SealStampLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long sealId;

    @Column(nullable = false, length = 64)
    private String sealHashAtStamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StampTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(length = 64)
    private String stampDocumentHash;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRevoked = false;

    private LocalDateTime revokedAt;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime stampedAt = LocalDateTime.now();

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 押印を取り消す。
     */
    public void revoke() {
        this.isRevoked = true;
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * 印鑑ハッシュを検証する。押印時のハッシュと現在の印鑑ハッシュを比較する。
     *
     * @param currentSealHash 現在の印鑑ハッシュ
     * @return ハッシュが一致する場合 true
     */
    public boolean verify(String currentSealHash) {
        return this.sealHashAtStamp.equals(currentSealHash);
    }

    /**
     * 取り消し済みかどうかを判定する。
     *
     * @return 取り消し済みの場合 true
     */
    public boolean isAlreadyRevoked() {
        return Boolean.TRUE.equals(this.isRevoked);
    }
}
