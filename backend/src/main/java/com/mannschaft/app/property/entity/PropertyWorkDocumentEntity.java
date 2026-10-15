package com.mannschaft.app.property.entity;

import com.mannschaft.app.property.DocumentKind;
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
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 物件履歴パッケージ ↔ F05.5 SharedFile の中間エンティティ。
 * 文書種別タグを保持する。
 * F09.13 設計書 §3 property_work_documents テーブル定義に対応。
 *
 * 論理削除なし。パッケージ削除時に CASCADE 削除される
 * （SharedFile 本体は残るので個別 UI で削除）。
 * 設計書では created_at のみ持つため BaseEntity を継承せず独立する。
 */
@Entity
@Table(name = "property_work_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class PropertyWorkDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long packageId;

    @Column(nullable = false)
    private Long sharedFileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DocumentKind documentKind;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 表示順を更新する。
     */
    public void updateDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    /**
     * 文書種別を変更する。
     */
    public void changeDocumentKind(DocumentKind documentKind) {
        this.documentKind = documentKind;
    }

    /**
     * 補足メモを更新する。
     */
    public void updateNote(String note) {
        this.note = note;
    }
}
