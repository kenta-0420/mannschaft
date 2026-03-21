package com.mannschaft.app.seal.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.seal.SealVariant;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 電子印鑑エンティティ。ユーザーごとの印鑑データ（SVG・ハッシュ）を管理する。
 */
@Entity
@Table(name = "electronic_seals")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ElectronicSealEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SealVariant variant;

    @Column(nullable = false, length = 20)
    private String displayText;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String svgData;

    @Column(nullable = false, length = 64)
    private String sealHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer generationVersion = 1;

    private LocalDateTime deletedAt;

    /**
     * 印鑑のSVGデータとハッシュを再生成する。
     *
     * @param newSvgData  新しいSVGデータ
     * @param newSealHash 新しいハッシュ値
     */
    public void regenerate(String newSvgData, String newSealHash) {
        this.svgData = newSvgData;
        this.sealHash = newSealHash;
        this.generationVersion = this.generationVersion + 1;
    }

    /**
     * 表示テキストを更新する。
     *
     * @param newDisplayText 新しい表示テキスト
     */
    public void updateDisplayText(String newDisplayText) {
        this.displayText = newDisplayText;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 削除済みかどうかを判定する。
     *
     * @return 削除済みの場合 true
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
