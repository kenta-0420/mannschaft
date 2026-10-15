package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 出席要件開示判断記録リポジトリ（F03.13 Phase 15）。 */
public interface AttendanceDisclosureRecordRepository
        extends JpaRepository<AttendanceDisclosureRecordEntity, Long> {

    /** 評価IDに紐づく開示判断記録を判断日降順で取得する。 */
    List<AttendanceDisclosureRecordEntity> findByEvaluationIdOrderByDecidedAtDesc(Long evaluationId);

    /** 生徒のユーザーIDに紐づく開示判断記録を判断日降順で取得する。 */
    List<AttendanceDisclosureRecordEntity> findByStudentUserIdOrderByDecidedAtDesc(Long studentUserId);

    /** 評価IDと判断種別で最新の開示判断記録を取得する（最新判断状態の確認用）。 */
    Optional<AttendanceDisclosureRecordEntity> findTopByEvaluationIdAndDecisionOrderByDecidedAtDesc(
            Long evaluationId, DisclosureDecision decision);
}
