package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    // --- §3.7 退会・チーム／組織削除時の後始末で使うスコープ一括 finder ---

    List<ScheduleKeepEntity> findAllByTeamId(Long teamId);

    List<ScheduleKeepEntity> findAllByOrganizationId(Long organizationId);

    List<ScheduleKeepEntity> findAllByUserId(Long userId);
}
