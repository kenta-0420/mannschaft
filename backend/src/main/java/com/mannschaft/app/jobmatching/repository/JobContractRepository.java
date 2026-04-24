package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 求人契約リポジトリ。
 */
public interface JobContractRepository extends JpaRepository<JobContractEntity, Long> {

    /**
     * Worker のマイ契約一覧を採用確定日時の新しい順で取得する。
     */
    List<JobContractEntity> findByWorkerUserIdOrderByMatchedAtDesc(Long workerId);

    /**
     * Requester のマイ契約一覧を採用確定日時の新しい順で取得する。
     */
    List<JobContractEntity> findByRequesterUserIdOrderByMatchedAtDesc(Long requesterId);

    /**
     * Worker または Requester 視点を問わず、ユーザーが関与する全契約をページング取得する
     * （マイ契約一覧 API の統合視点）。
     */
    @Query("SELECT c FROM JobContractEntity c "
            + "WHERE c.workerUserId = :userId OR c.requesterUserId = :userId")
    Page<JobContractEntity> findByUserInvolvement(@Param("userId") Long userId, Pageable pageable);

    /**
     * 応募から契約を逆引きする（一応募 ↔ 一契約のユニーク関係）。
     */
    Optional<JobContractEntity> findByJobApplicationId(Long applicationId);

    /**
     * 契約を排他ロック付きで取得する（QR チェックイン／アウト処理の競合制御用）。
     *
     * <p>同一契約に対する同時 IN/OUT スキャンや、チェックイン中のステータス遷移と
     * 他オペレーション（キャンセル等）の競合を PESSIMISTIC_WRITE で物理排他する。
     * 楽観的ロック（{@code @Version}）と併用する二重防御。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM JobContractEntity c WHERE c.id = :id")
    Optional<JobContractEntity> findByIdForUpdate(@Param("id") Long id);
}
