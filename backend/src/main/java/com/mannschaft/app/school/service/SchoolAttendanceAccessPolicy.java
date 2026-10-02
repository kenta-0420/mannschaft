package com.mannschaft.app.school.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.dto.AttendancePermissionsResponse;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 学校出欠の認可判定を一元化するポリシー（CMP-261001-0630 / CMP-260930-0230 第1段）。
 *
 * <p>学校ドメインの Service は {@code AccessControlService} の素の所属・管理者判定
 * （{@code checkMembership} 等）を直接呼ばず、必ず本クラス経由で判定する（番人
 * {@code SchoolAttendanceAuthzGuardArchTest}）。</p>
 *
 * <h2>判定</h2>
 * <ul>
 *   <li><b>V（閲覧）</b>: チームの ADMIN/DEPUTY_ADMIN、または現役の担任・副担任、
 *       または {@code VIEW_ATTENDANCE} 委任者。</li>
 *   <li><b>R（日次登録）</b>: 現役の担任・副担任、または ADMIN/DEPUTY_ADMIN。</li>
 *   <li><b>P（時限の登録・修正）</b>: 第1段は R と同じ。第2段で教科担当を差し込めるよう、
 *       判定メソッドを R から独立させている（{@link #canRecordPeriod}）。</li>
 *   <li>アラート解決・保護者連絡の確認/反映は R。</li>
 * </ul>
 *
 * <h2>現役の担任</h2>
 * <ul>
 *   <li>担任・副担任の資格は、その人が現在そのチームの有効なメンバー（{@code memberships.left_at IS NULL}）
 *       であることも条件にする（チームを離れた担任の名簿行が残っていても資格を与えない）。</li>
 *   <li>{@code effective_from <= 今日 <= effective_until}（until が null なら無期限）の行が現役。
 *       今日は業務ゾーン（{@code wallClock}）の日付。</li>
 *   <li>現役行が複数あるときは、全行の担任と副担任の<b>和集合</b>を資格とする（行の選択はしない）。</li>
 *   <li>{@code assistant_teacher_user_ids} が不正な形（配列でない・数値以外の要素を含む等）のときは、
 *       その行の<b>副担任資格だけ</b>を無効にし、主担任は有効のままとする。例外は投げない。</li>
 * </ul>
 *
 * <p>SYSTEM_ADMIN には資格を与えない（{@code isAdminOrAbove} はテナント ADMIN のみを真とする）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class SchoolAttendanceAccessPolicy {

    /** 出欠・学級担任情報の閲覧権限名（{@code permissions.name} の値）。権限グループ経由で委任される。 */
    static final String PERMISSION_VIEW_ATTENDANCE = "VIEW_ATTENDANCE";

    private static final String SCOPE_TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final ClassHomeroomRepository classHomeroomRepository;
    private final ObjectMapper objectMapper;
    private final Clock wallClock;

    public SchoolAttendanceAccessPolicy(
            AccessControlService accessControlService,
            ClassHomeroomRepository classHomeroomRepository,
            ObjectMapper objectMapper,
            @Qualifier("wallClock") Clock wallClock) {
        this.accessControlService = accessControlService;
        this.classHomeroomRepository = classHomeroomRepository;
        this.objectMapper = objectMapper;
        this.wallClock = wallClock;
    }

    // ========================================
    // 判定（boolean）
    // ========================================

    /** V: 閲覧できるか。 */
    public boolean canView(Long userId, Long teamId) {
        if (userId == null || teamId == null) {
            return false;
        }
        return accessControlService.isAdminOrAbove(userId, teamId, SCOPE_TEAM)
                || isActiveHomeroomTeacher(userId, teamId)
                || accessControlService.hasPermission(userId, teamId, SCOPE_TEAM, PERMISSION_VIEW_ATTENDANCE);
    }

    /** R: 日次出欠の登録（および書込系のアラート解決・保護者連絡の確認/反映）ができるか。 */
    public boolean canRecordDaily(Long userId, Long teamId) {
        if (userId == null || teamId == null) {
            return false;
        }
        return accessControlService.isAdminOrAbove(userId, teamId, SCOPE_TEAM)
                || isActiveHomeroomTeacher(userId, teamId);
    }

    /**
     * P: 時限出欠の登録・修正ができるか。
     *
     * <p>第1段は R と同じ。第2段で教科担当（教科×クラス×先生マスター、期限付き代理委任）を
     * ここへ差し込む。呼び出し側は R と混同せず、時限の書込には必ず本メソッドを使うこと。</p>
     */
    public boolean canRecordPeriod(Long userId, Long teamId) {
        return canRecordDaily(userId, teamId);
    }

    /** 現役の担任・副担任か（V/R/P の共通部品。管理者かどうかは見ない）。 */
    public boolean isActiveHomeroomTeacher(Long userId, Long teamId) {
        if (userId == null || teamId == null) {
            return false;
        }
        LocalDate today = LocalDate.now(wallClock);
        List<ClassHomeroomEntity> activeRows = classHomeroomRepository.findActiveByTeamId(teamId, today);
        for (ClassHomeroomEntity row : activeRows) {
            if (userId.equals(row.getHomeroomTeacherUserId())
                    || parseAssistantIds(row.getAssistantTeacherUserIds(), row.getId()).contains(userId)) {
                // 名簿に載っているだけでは資格を与えない。退職などでチームを離れた担任の行が残っていても、
                // 現在そのチームの有効なメンバーでなければ担任・副担任としては扱わない。
                return accessControlService.isMember(userId, teamId, SCOPE_TEAM);
            }
        }
        return false;
    }

    // ========================================
    // 登録入力の整合（認可の一部として Policy に集約）
    // ========================================

    /**
     * 登録 entries の生徒が全員、そのクラスの在籍メンバー（有効な membership）であることを要求する。
     *
     * <p>在籍者の集合を <b>1 クエリ</b>で取得して突き合わせる（entries 件数に依存してクエリを増やさない）。
     * 1 人でも在籍者でなければ {@link SchoolErrorCode#STUDENT_NOT_ENROLLED}（400）。呼び出し元の
     * トランザクションごと全件ロールバックされる（部分登録なし）。</p>
     */
    public void requireEnrolledStudents(Long teamId, Collection<Long> studentUserIds) {
        Set<Long> enrolled = new HashSet<>(accessControlService.listActiveMemberIds(teamId, SCOPE_TEAM));
        for (Long studentUserId : studentUserIds) {
            if (studentUserId == null || !enrolled.contains(studentUserId)) {
                throw new BusinessException(SchoolErrorCode.STUDENT_NOT_ENROLLED);
            }
        }
    }

    // ========================================
    // 判定（権限なしなら 403 COMMON_002）
    // ========================================

    /** V を要求する。権限なしは 403 COMMON_002。 */
    public void checkCanView(Long userId, Long teamId) {
        if (!canView(userId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /** R を要求する。権限なしは 403 COMMON_002。 */
    public void checkCanRecordDaily(Long userId, Long teamId) {
        if (!canRecordDaily(userId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /** P を要求する。権限なしは 403 COMMON_002。 */
    public void checkCanRecordPeriod(Long userId, Long teamId) {
        if (!canRecordPeriod(userId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // 判定結果 API 用
    // ========================================

    /**
     * V/R/P の判定結果をまとめて返す（AC-23）。権限なし・非所属・存在しないチームでも例外にせず全 false。
     */
    public AttendancePermissionsResponse resolvePermissions(Long userId, Long teamId) {
        return new AttendancePermissionsResponse(
                teamId,
                canView(userId, teamId),
                canRecordDaily(userId, teamId),
                canRecordPeriod(userId, teamId));
    }

    // ========================================
    // 内部
    // ========================================

    /**
     * 副担任 JSON を userId 集合に変換する。不正な形（配列でない・整数以外の要素・桁あふれを含む）は
     * <b>空集合</b>（＝この行の副担任資格なし）を返し、例外は投げない。主担任の判定には影響しない。
     */
    private Set<Long> parseAssistantIds(String json, Long homeroomRowId) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                return invalidAssistants(homeroomRowId);
            }
            Set<Long> ids = new HashSet<>();
            for (JsonNode element : root) {
                if (!element.isIntegralNumber() || !element.canConvertToLong()) {
                    return invalidAssistants(homeroomRowId);
                }
                ids.add(element.longValue());
            }
            return ids;
        } catch (JsonProcessingException e) {
            return invalidAssistants(homeroomRowId);
        }
    }

    private Set<Long> invalidAssistants(Long homeroomRowId) {
        log.warn("class_homerooms.assistant_teacher_user_ids の形が不正なため副担任資格を無効化: id={}", homeroomRowId);
        return Set.of();
    }
}
