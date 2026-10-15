package com.mannschaft.app.event.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.event.EventDelegationStatus;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * イベント代理出席委任状リポジトリ（F03.10）。
 *
 * <p>{@code organization_id} を持つテナントスコープのため {@link AbstractTenantAwareRepository} を継承する（原則7）。
 * 後続陣（Service/Controller）が必要とする最小限の finder のみを定義する。</p>
 */
public interface EventDelegationRepository
        extends AbstractTenantAwareRepository<EventDelegationEntity, UUID> {

    /**
     * 委任者視点: 指定イベント × 委任者の、指定ステータス群に該当する委任を 1 件取得する。
     *
     * <p>アクティブ（{@code PENDING}/{@code ACCEPTED}）委任の重複チェック（§5.6 #5）に使用する。</p>
     */
    Optional<EventDelegationEntity> findFirstByEventIdAndDelegatorIdAndStatusIn(
            Long eventId, Long delegatorId, Collection<EventDelegationStatus> statuses);

    /**
     * 代理人視点: 指定イベント × 代理人の、指定ステータス群に該当する委任を取得する。
     */
    List<EventDelegationEntity> findByEventIdAndDelegateIdAndStatusIn(
            Long eventId, Long delegateId, Collection<EventDelegationStatus> statuses);

    /**
     * 連鎖代理禁止チェック（§5.6 #6）用: 代理人が他者の代理として指定ステータス群に存在するか。
     */
    boolean existsByDelegateIdAndStatusIn(
            Long delegateId, Collection<EventDelegationStatus> statuses);

    /**
     * ADMIN 向け一覧: 指定イベントの委任を作成日時降順でページング取得する（§4.2）。
     */
    Page<EventDelegationEntity> findByEventIdOrderByCreatedAtDesc(
            Long eventId, Pageable pageable);

    /**
     * 指定イベントの委任総件数（一覧 total 算出用）。
     */
    long countByEventId(Long eventId);

    /**
     * 退会連動（§5.8）/ クリーンアップバッチ用: 指定スコープ（org または team）で、委任者または代理人が
     * 指定ユーザーであるアクティブな委任を取得する。
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d FROM EventDelegationEntity d
            WHERE d.status IN :statuses
              AND (d.delegatorId = :userId OR d.delegateId = :userId)
              AND ((:organizationId IS NOT NULL AND d.organizationId = :organizationId)
                OR (:teamId IS NOT NULL AND d.teamId = :teamId))
            """)
    List<EventDelegationEntity> findActiveByScopeAndInvolvedUser(
            @org.springframework.data.repository.query.Param("organizationId") Long organizationId,
            @org.springframework.data.repository.query.Param("teamId") Long teamId,
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("statuses") Collection<EventDelegationStatus> statuses);

    /**
     * クリーンアップバッチ用: アクティブ（PENDING/ACCEPTED）委任を全件取得する。
     */
    List<EventDelegationEntity> findByStatusIn(Collection<EventDelegationStatus> statuses);
}
