package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobCheckInEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * QR チェックイン／アウト実績リポジトリ。F13.1 Phase 13.1.2。
 */
public interface JobCheckInRepository extends JpaRepository<JobCheckInEntity, Long> {

    /**
     * 指定契約・種別のチェックイン／アウト実績を取得する（IN/OUT は各 1 件のみ）。
     */
    Optional<JobCheckInEntity> findByJobContractIdAndType(Long jobContractId, JobCheckInType type);

    /**
     * 指定 Worker が、指定時間帯（{@code from} 以上 {@code to} 未満相当の JPA Between 判定）に、
     * 別契約で同一種別のチェックインを行っているかを判定する。
     *
     * <p>掛け持ち禁止ルール（設計書 §2.3.1 末尾）の実現に使用する:
     * 「同一 Worker が同時刻に別契約でチェックインしている場合は拒否（掛け持ち禁止、400 応答）」。</p>
     *
     * @param workerUserId Worker のユーザー ID
     * @param from 開始時刻（両端含む、JPA Between 仕様）
     * @param to 終了時刻（両端含む、JPA Between 仕様）
     * @param type IN または OUT
     * @param excludeContractId 除外対象の契約 ID（スキャン処理中の自契約自身）
     */
    boolean existsByWorkerUserIdAndScannedAtBetweenAndTypeAndJobContractIdNot(
            Long workerUserId,
            Instant from,
            Instant to,
            JobCheckInType type,
            Long excludeContractId);
}
