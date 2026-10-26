package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.visibility.ScheduleKeepVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * キープ（日付未定の予定）リポジトリ（F03.17）。
 *
 * <p>設計書 §4.6.3（IDOR 防御）に従い、<b>スコープ込みの finder のみを定義する</b>。
 * {@code findById(id)} 単独の finder はここに生やさない
 * （{@code keepId} だけで検索し後からスコープを比較する実装は、比較を忘れた瞬間に IDOR になる）。
 * Controller/Service は必ず「パスのスコープ」を伴う finder を使うこと。</p>
 */
public interface ScheduleKeepRepository extends JpaRepository<ScheduleKeepEntity, UUID> {

    // --- チームスコープ ---

    Optional<ScheduleKeepEntity> findByIdAndTeamId(UUID id, Long teamId);

    Page<ScheduleKeepEntity> findByTeamIdAndStatus(Long teamId, ScheduleKeepStatus status, Pageable pageable);

    Page<ScheduleKeepEntity> findByTeamId(Long teamId, Pageable pageable);

    // --- 組織スコープ ---

    Optional<ScheduleKeepEntity> findByIdAndOrganizationId(UUID id, Long organizationId);

    Page<ScheduleKeepEntity> findByOrganizationIdAndStatus(
            Long organizationId, ScheduleKeepStatus status, Pageable pageable);

    Page<ScheduleKeepEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    // --- 個人スコープ ---

    Optional<ScheduleKeepEntity> findByIdAndUserId(UUID id, Long userId);

    Page<ScheduleKeepEntity> findByUserIdAndStatus(Long userId, ScheduleKeepStatus status, Pageable pageable);

    Page<ScheduleKeepEntity> findByUserId(Long userId, Pageable pageable);

    // --- 逆引き（§4.5.1）。スコープ列を先頭に置く（等値絞り込みが効くため・§3.3.3） ---

    List<ScheduleKeepEntity> findByTeamIdAndConvertedScheduleIdIn(Long teamId, List<Long> convertedScheduleIds);

    List<ScheduleKeepEntity> findByOrganizationIdAndConvertedScheduleIdIn(
            Long organizationId, List<Long> convertedScheduleIds);

    List<ScheduleKeepEntity> findByUserIdAndConvertedScheduleIdIn(Long userId, List<Long> convertedScheduleIds);

    // --- 単体逆引き（by-schedule）。複数ヒット時は created_at 最新の1件を採る（§4.5.1 の決定則） ---
    //
    // converted_schedule_id に一意制約は張れない（revert → 再 convert の履歴で、理論上は複数の
    // キープが同じ予定 ID を指しうる）。よって「1件返す finder」ではなく順序付きの List を返し、
    // 呼び出し側が先頭を採る。ここで Optional を返す finder にすると、履歴が増えた瞬間に
    // NonUniqueResultException で 500 になる。

    List<ScheduleKeepEntity> findByTeamIdAndConvertedScheduleIdOrderByCreatedAtDescIdDesc(
            Long teamId, Long convertedScheduleId);

    List<ScheduleKeepEntity> findByOrganizationIdAndConvertedScheduleIdOrderByCreatedAtDescIdDesc(
            Long organizationId, Long convertedScheduleId);

    List<ScheduleKeepEntity> findByUserIdAndConvertedScheduleIdOrderByCreatedAtDescIdDesc(
            Long userId, Long convertedScheduleId);

    // --- 件数上限（§10.1）。KEPT のみを数える（ARCHIVED/SCHEDULED は枠を消費しない） ---

    long countByTeamIdAndStatus(Long teamId, ScheduleKeepStatus status);

    long countByOrganizationIdAndStatus(Long organizationId, ScheduleKeepStatus status);

    long countByUserIdAndStatus(Long userId, ScheduleKeepStatus status);

    // --- F00 可視性判定用の射影取得（§4.6.4 手順8） ---

    /**
     * 可視性判定に必要な列だけを 1 SQL で一括取得する（{@code ScheduleKeepVisibilityResolver} 専用）。
     *
     * <p>本メソッドが「スコープを取らない finder」に見えるのは意図的である。
     * {@code ScheduleKeepVisibilityResolver} の責務は<b>レコードのスコープを読み取って
     * 閲覧可否を判定すること</b>であり、スコープを入力として絞り込んでしまうと判定材料が消える。
     * パスのスコープとの一致検証（IDOR 防御・§4.6.3）は本メソッドではなく
     * {@code ScheduleKeepAccessGuard} がスコープ込み finder で行う。</p>
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは返らない
     * （不在＝fail-closed で存在を漏らさない）。</p>
     *
     * @param ids 判定対象のキープ ID 集合
     * @return 実在するキープの射影（順序は保証しない）
     */
    List<ScheduleKeepVisibilityProjection> findVisibilityProjectionsByIdIn(Collection<UUID> ids);

    // --- §3.7 退会・チーム／組織削除時の後始末で使うスコープ一括 finder ---

    List<ScheduleKeepEntity> findAllByTeamId(Long teamId);

    List<ScheduleKeepEntity> findAllByOrganizationId(Long organizationId);

    List<ScheduleKeepEntity> findAllByUserId(Long userId);
}
