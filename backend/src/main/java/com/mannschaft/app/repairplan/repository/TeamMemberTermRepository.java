package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 理事任期リポジトリ。
 *
 * <p>論理削除を持たない履歴テーブルのため {@code AbstractTenantAwareRepository} は継承せず
 * {@link JpaRepository} を直接継承する。テナント絞り込みは独自メソッドで提供する。</p>
 */
public interface TeamMemberTermRepository extends JpaRepository<TeamMemberTerm, UUID> {

    /** テナント単位の任期データ取得。 */
    List<TeamMemberTerm> findByOrganizationId(Long organizationId);

    /** スコープ × ユーザー単位の任期履歴取得。 */
    List<TeamMemberTerm> findByScopeTypeAndScopeIdAndUserIdOrderByTermStartAsc(
            String scopeType, Long scopeId, Long userId);

    /** スコープ単位の現行任期者リスト。 */
    List<TeamMemberTerm> findByScopeTypeAndScopeIdAndIsActiveTrueOrderByTermEndAsc(
            String scopeType, Long scopeId);

    /** 30 日前催促 cron 用: 任期終了が指定日範囲内の現役理事。 */
    List<TeamMemberTerm> findByIsActiveTrueAndTermEndBetween(LocalDate from, LocalDate to);

    /** 90 日後 demote cron 用: 任期終了から 90 日経過した現役フラグ。 */
    List<TeamMemberTerm> findByIsActiveTrueAndTermEndBefore(LocalDate before);

    /** タイムライン用: スコープの全任期を取得（期間昇順）。 */
    List<TeamMemberTerm> findByScopeTypeAndScopeIdOrderByTermStartAsc(String scopeType, Long scopeId);
}
