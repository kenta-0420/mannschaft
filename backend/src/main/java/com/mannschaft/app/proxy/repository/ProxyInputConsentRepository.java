package com.mannschaft.app.proxy.repository;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 代理入力同意書リポジトリ（F14.1）。
 * @SQLRestriction により deleted_at IS NULL が自動適用される。
 */
public interface ProxyInputConsentRepository extends JpaRepository<ProxyInputConsentEntity, Long> {

    /**
     * 代理者が保有する有効な同意書一覧を取得する（ProxyInputDeskView起動時に使用）。
     */
    @Query("SELECT c FROM ProxyInputConsentEntity c WHERE c.proxyUserId = :proxyUserId " +
           "AND c.approvedAt IS NOT NULL AND c.revokedAt IS NULL " +
           "AND c.effectiveFrom <= CURRENT_DATE AND c.effectiveUntil >= CURRENT_DATE")
    List<ProxyInputConsentEntity> findActiveByProxyUserId(@Param("proxyUserId") Long proxyUserId);

    /**
     * ProxyInputContextFilterで同意書の有効性を検証する。
     * consentIdとproxyUserIdの両方が一致する有効な同意書のみ返す。
     */
    @Query("SELECT c FROM ProxyInputConsentEntity c WHERE c.id = :consentId " +
           "AND c.proxyUserId = :proxyUserId " +
           "AND c.approvedAt IS NOT NULL AND c.revokedAt IS NULL " +
           "AND c.effectiveFrom <= CURRENT_DATE AND c.effectiveUntil >= CURRENT_DATE")
    Optional<ProxyInputConsentEntity> findValidConsent(
            @Param("consentId") Long consentId,
            @Param("proxyUserId") Long proxyUserId);

    /**
     * 同一組み合わせの有効同意書が存在するかチェックする（二重登録防止）。
     * DB側のUNIQUE制約ではなくService層でチェックする方式（MySQL NULL制約の回避）。
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM ProxyInputConsentEntity c " +
           "WHERE c.subjectUserId = :subjectUserId AND c.proxyUserId = :proxyUserId " +
           "AND c.organizationId = :organizationId AND c.effectiveFrom = :effectiveFrom " +
           "AND c.revokedAt IS NULL AND c.approvedAt IS NOT NULL")
    boolean existsActiveConsent(
            @Param("subjectUserId") Long subjectUserId,
            @Param("proxyUserId") Long proxyUserId,
            @Param("organizationId") Long organizationId,
            @Param("effectiveFrom") LocalDate effectiveFrom);

    /**
     * 組合単位の同意書一覧を取得する（ADMIN向け管理画面）。
     */
    List<ProxyInputConsentEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /**
     * 承認待ちの同意書一覧を取得する。
     */
    @Query("SELECT c FROM ProxyInputConsentEntity c WHERE c.organizationId = :organizationId " +
           "AND c.approvedAt IS NULL AND c.revokedAt IS NULL")
    List<ProxyInputConsentEntity> findPendingApproval(@Param("organizationId") Long organizationId);

    /**
     * 本人ユーザーIDで有効な同意書一覧を取得する（ライフイベント失効処理用）。
     */
    @Query("SELECT c FROM ProxyInputConsentEntity c WHERE c.subjectUserId = :userId " +
           "AND c.revokedAt IS NULL")
    List<ProxyInputConsentEntity> findActiveBySubjectUserId(@Param("userId") Long userId);

    /**
     * 有効期限切れの同意書を取得する（ProxyConsentExpiryJobで使用）。
     */
    @Query("SELECT c FROM ProxyInputConsentEntity c WHERE c.effectiveUntil < CURRENT_DATE " +
           "AND c.revokedAt IS NULL")
    List<ProxyInputConsentEntity> findExpired();
}
