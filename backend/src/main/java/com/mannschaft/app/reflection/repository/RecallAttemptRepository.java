package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.entity.RecallAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RecallAttemptEntity} のリポジトリ（F06.5・§2.4 / §3.1）。
 */
@Repository
public interface RecallAttemptRepository extends JpaRepository<RecallAttemptEntity, UUID> {

    /**
     * マスク判定（§3.1 step5）で使う「直近想起予定日 dLast 以降の最新 recall」を引く。
     *
     * @param entryId    エントリID
     * @param recallDate 直近で到来した想起予定日（dLast）
     * @return dLast 以降で最新の recall（無ければ empty → 未想起＝マスク）
     */
    Optional<RecallAttemptEntity> findTopByEntryIdAndRecallDateGreaterThanEqualOrderByRecallDateDesc(
            UUID entryId, LocalDate recallDate);

    /** 想起履歴一覧（§7 #11）。 */
    List<RecallAttemptEntity> findByEntryIdOrderByRecallDateDesc(UUID entryId);
}
