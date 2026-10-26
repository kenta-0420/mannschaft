package com.mannschaft.app.property.repository;

import com.mannschaft.app.property.DocumentKind;
import com.mannschaft.app.property.entity.PropertyWorkDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 物件履歴文書（中間テーブル）リポジトリ。
 * F09.13 設計書 §3 property_work_documents に対応。
 */
public interface PropertyWorkDocumentRepository
        extends JpaRepository<PropertyWorkDocumentEntity, Long> {

    /**
     * パッケージ配下の文書を表示順で取得する。
     */
    List<PropertyWorkDocumentEntity> findByPackageIdOrderByDisplayOrderAscIdAsc(Long packageId);

    /**
     * パッケージ × 文書種別で文書を取得する。
     */
    List<PropertyWorkDocumentEntity> findByPackageIdAndDocumentKindOrderByDisplayOrderAsc(
            Long packageId, DocumentKind documentKind);

    /**
     * パッケージ ↔ ファイルの一意性チェック。
     */
    Optional<PropertyWorkDocumentEntity> findByPackageIdAndSharedFileId(
            Long packageId, Long sharedFileId);

    /**
     * 指定 SharedFile を参照する全パッケージ ID を取得する（削除連動チェック用）。
     */
    List<PropertyWorkDocumentEntity> findBySharedFileId(Long sharedFileId);

    /**
     * パッケージ配下の文書件数を取得する。
     */
    long countByPackageId(Long packageId);

    /**
     * パッケージの全文書を物理削除する（パッケージ論理削除時の補助）。
     */
    @Modifying
    @Query("DELETE FROM PropertyWorkDocumentEntity d WHERE d.packageId = :packageId")
    int deleteByPackageId(@Param("packageId") Long packageId);
}
