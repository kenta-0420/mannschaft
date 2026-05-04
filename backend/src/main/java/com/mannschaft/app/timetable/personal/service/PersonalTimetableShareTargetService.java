package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableShareTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.15 Phase 5 個人時間割の家族チーム共有先サービス。
 *
 * <p>主な責務:</p>
 * <ul>
 *   <li>家族チームへの共有先追加 / 解除（最大3件、家族チーム限定）</li>
 *   <li>共有先追加時に visibility を自動的に {@code FAMILY_SHARED} に切替</li>
 *   <li>全共有先解除時に visibility を {@code PRIVATE} に戻す</li>
 *   <li>F11 監査ログ（{@code personal_timetable.share_added} / {@code share_removed}
 *       / {@code visibility_changed}）への記録</li>
 * </ul>
 *
 * <p>家族チーム判定: {@code teams.template = 'family'} を採用する（既存 F01.4 と同じ規約）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableShareTargetService {

    /** 1個人時間割あたりの共有先上限（設計書 §3）。 */
    public static final int MAX_SHARE_TARGETS_PER_TIMETABLE = 3;

    /** 家族チーム判定に使う {@code teams.template} の値。 */
    public static final String FAMILY_TEMPLATE_SLUG = "family";

    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetableShareTargetRepository shareTargetRepository;
    private final TeamRepository teamRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;

    /**
     * 共有先一覧を取得する。所有者検証込み。
     */
    public List<PersonalTimetableShareTargetEntity> list(Long personalTimetableId, Long userId) {
        ensureOwned(personalTimetableId, userId);
        return shareTargetRepository.findByPersonalTimetableId(personalTimetableId);
    }

    /**
     * チームの表示名を取得する（DTO の team_name 補完用）。存在しない場合は null。
     */
    public String resolveTeamName(Long teamId) {
        return teamRepository.findById(teamId).map(TeamEntity::getName).orElse(null);
    }

    /**
     * 共有先を追加する。
     *
     * <ul>
     *   <li>上限3件超過は 409 (SHARE_TARGET_LIMIT_EXCEEDED)</li>
     *   <li>同一チームの重複登録は 409 (SHARE_TARGET_DUPLICATED)</li>
     *   <li>チームが存在しない / FAMILY 以外: 422 (SHARE_TARGET_NOT_FAMILY_TEAM) または 404</li>
     *   <li>currentUser がそのチームの MEMBER+ でない: 403 (SHARE_TARGET_NOT_TEAM_MEMBER)</li>
     * </ul>
     *
     * <p>追加が成功し、かつ visibility が PRIVATE だった場合、{@code FAMILY_SHARED} に
     * 自動的に切り替える（設計書 §4 参照）。</p>
     */
    @Transactional
    public PersonalTimetableShareTargetEntity add(
            Long personalTimetableId, Long userId, Long teamId) {
        PersonalTimetableEntity timetable = ensureOwned(personalTimetableId, userId);

        // 1) チーム存在＆ FAMILY であることを検証
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.SHARE_TARGET_TEAM_NOT_FOUND));
        if (!FAMILY_TEMPLATE_SLUG.equals(team.getTemplate())) {
            throw new BusinessException(PersonalTimetableErrorCode.SHARE_TARGET_NOT_FAMILY_TEAM);
        }

        // 2) currentUser が MEMBER+ で所属しているか
        if (!userRoleRepository.existsByUserIdAndTeamId(userId, teamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.SHARE_TARGET_NOT_TEAM_MEMBER);
        }

        // 3) 上限チェック
        long current = shareTargetRepository.countByPersonalTimetableId(personalTimetableId);
        if (current >= MAX_SHARE_TARGETS_PER_TIMETABLE) {
            throw new BusinessException(PersonalTimetableErrorCode.SHARE_TARGET_LIMIT_EXCEEDED);
        }

        // 4) 重複チェック
        if (shareTargetRepository.existsByPersonalTimetableIdAndTeamId(personalTimetableId, teamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.SHARE_TARGET_DUPLICATED);
        }

        // 5) 追加
        PersonalTimetableShareTargetEntity entity = PersonalTimetableShareTargetEntity.builder()
                .personalTimetableId(personalTimetableId)
                .teamId(teamId)
                .build();
        PersonalTimetableShareTargetEntity saved = shareTargetRepository.save(entity);

        // 6) visibility 自動切替（PRIVATE → FAMILY_SHARED）
        boolean visibilityChanged = false;
        PersonalTimetableVisibility before = timetable.getVisibility();
        if (before != PersonalTimetableVisibility.FAMILY_SHARED) {
            PersonalTimetableEntity updated = timetable.toBuilder()
                    .visibility(PersonalTimetableVisibility.FAMILY_SHARED)
                    .build();
            personalTimetableRepository.save(updated);
            visibilityChanged = true;
        }

        // 7) 監査ログ
        auditLogService.record(
                "personal_timetable.share_added",
                userId, null, teamId, null, null, null, null,
                String.format(
                        "{\"source\":\"PERSONAL_TIMETABLE\",\"source_id\":%d,\"team_id\":%d}",
                        personalTimetableId, teamId));
        if (visibilityChanged) {
            auditLogService.record(
                    "personal_timetable.visibility_changed",
                    userId, null, null, null, null, null, null,
                    String.format(
                            "{\"source\":\"PERSONAL_TIMETABLE\",\"source_id\":%d,"
                                    + "\"before\":\"%s\",\"after\":\"FAMILY_SHARED\"}",
                            personalTimetableId, before.name()));
        }

        log.info("個人時間割の家族共有先を追加: pid={}, teamId={}, userId={}",
                personalTimetableId, teamId, userId);
        return saved;
    }

    /**
     * 共有先を解除する。最後の1件を削除した場合は visibility を PRIVATE に戻す。
     */
    @Transactional
    public void remove(Long personalTimetableId, Long userId, Long teamId) {
        PersonalTimetableEntity timetable = ensureOwned(personalTimetableId, userId);

        if (!shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                personalTimetableId, teamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.SHARE_TARGET_NOT_FOUND);
        }
        shareTargetRepository.deleteByPersonalTimetableIdAndTeamId(personalTimetableId, teamId);

        // 全件0になったら visibility を PRIVATE に戻す
        long remaining = shareTargetRepository.countByPersonalTimetableId(personalTimetableId);
        boolean visibilityChanged = false;
        PersonalTimetableVisibility before = timetable.getVisibility();
        if (remaining == 0
                && before == PersonalTimetableVisibility.FAMILY_SHARED) {
            PersonalTimetableEntity updated = timetable.toBuilder()
                    .visibility(PersonalTimetableVisibility.PRIVATE)
                    .build();
            personalTimetableRepository.save(updated);
            visibilityChanged = true;
        }

        // 監査ログ
        auditLogService.record(
                "personal_timetable.share_removed",
                userId, null, teamId, null, null, null, null,
                String.format(
                        "{\"source\":\"PERSONAL_TIMETABLE\",\"source_id\":%d,\"team_id\":%d}",
                        personalTimetableId, teamId));
        if (visibilityChanged) {
            auditLogService.record(
                    "personal_timetable.visibility_changed",
                    userId, null, null, null, null, null, null,
                    String.format(
                            "{\"source\":\"PERSONAL_TIMETABLE\",\"source_id\":%d,"
                                    + "\"before\":\"FAMILY_SHARED\",\"after\":\"PRIVATE\"}",
                            personalTimetableId));
        }

        log.info("個人時間割の家族共有先を解除: pid={}, teamId={}, userId={}, remaining={}",
                personalTimetableId, teamId, userId, remaining);
    }

    /**
     * 個人時間割の所有者検証。404 統一。
     */
    private PersonalTimetableEntity ensureOwned(Long personalTimetableId, Long userId) {
        return personalTimetableRepository
                .findByIdAndUserIdAndDeletedAtIsNull(personalTimetableId, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));
    }
}
