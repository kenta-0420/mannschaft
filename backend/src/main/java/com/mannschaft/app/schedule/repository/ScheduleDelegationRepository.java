package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * スケジュール代理出席委任状リポジトリ（F03.10）。
 *
 * <p>{@code organization_id} を持つテナントスコープのため {@link AbstractTenantAwareRepository} を継承する（原則7）。
 * 後続陣（Service/Controller）が必要とする最小限の finder のみを定義する。</p>
 */
public interface ScheduleDelegationRepository
        extends AbstractTenantAwareRepository<ScheduleDelegationEntity, UUID> {

    /**
     * 委任者視点: 指定スケジュール × 委任者の、指定ステータス群に該当する委任を 1 件取得する。
     *
     * <p>アクティブ（{@code PENDING}/{@code ACCEPTED}）委任の重複チェック（§5.6 #5）に使用する。
     * アクティブ委任は DB の UNIQUE 制約で最大 1 件に保証されるため先頭 1 件で十分。</p>
     */
    Optional<ScheduleDelegationEntity> findFirstByScheduleIdAndDelegatorIdAndStatusIn(
            Long scheduleId, Long delegatorId, Collection<ScheduleDelegationStatus> statuses);

    /**
     * 代理人視点: 指定スケジュール × 代理人の、指定ステータス群に該当する委任を取得する。
     *
     * <p>代理人の承認/拒否待ち（{@code PENDING}）や確定済み（{@code ACCEPTED}）委任の解決に使用する。</p>
     */
    List<ScheduleDelegationEntity> findByScheduleIdAndDelegateIdAndStatusIn(
            Long scheduleId, Long delegateId, Collection<ScheduleDelegationStatus> statuses);

    /**
     * 連鎖代理禁止チェック（§5.6 #6）用: 代理人が他者の代理として指定ステータス群に存在するか。
     */
    boolean existsByDelegateIdAndStatusIn(
            Long delegateId, Collection<ScheduleDelegationStatus> statuses);

    /**
     * ADMIN 向け一覧: 指定スケジュールの委任を作成日時降順でページング取得する（§4.1）。
     */
    Page<ScheduleDelegationEntity> findByScheduleIdOrderByCreatedAtDesc(
            Long scheduleId, Pageable pageable);

    /**
     * 指定スケジュールの委任総件数（一覧 total 算出用）。
     */
    long countByScheduleId(Long scheduleId);
}
