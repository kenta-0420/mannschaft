package com.mannschaft.app.succession.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.succession.entity.UnsealAuditViewEntity;

import java.util.List;
import java.util.UUID;

/**
 * 開封中閲覧履歴リポジトリ（F09.15・append-only）。
 *
 * <p>UPDATE / DELETE は Service 層で禁止する想定（このリポジトリでは
 * 検索メソッドのみを提供）。
 */
public interface UnsealAuditViewRepository
        extends AbstractTenantAwareRepository<UnsealAuditViewEntity, UUID> {

    /** 解除申請単位の閲覧履歴（閲覧日時降順）。 */
    List<UnsealAuditViewEntity> findByUnsealRequestIdAndDeletedAtIsNullOrderByViewedAtDesc(
            UUID unsealRequestId);

    /** 閲覧者ユーザー単位の閲覧履歴（閲覧日時降順・監査ダッシュボード用）。 */
    List<UnsealAuditViewEntity> findByViewerUserIdAndDeletedAtIsNullOrderByViewedAtDesc(
            Long viewerUserId);
}
