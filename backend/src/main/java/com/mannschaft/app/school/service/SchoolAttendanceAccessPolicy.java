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
 * <p>SYSTEM_ADMIN には資格を与えない（{@code isAdminOrAbove} はスコープ付きの ADMIN/DEPUTY_ADMIN のみを真とする。
 * SYSTEM_ADMIN と当該スコープの副管理者を兼任する利用者は、スコープ付きの資格で許可される）。</p>
 *
 * <h2>トランザクションの外で呼ぶこと</h2>
 * <p>本クラスは {@code @Transactional} を持たない。判定は {@code AccessControlService} 経由で role・membership・family の
 * Repository に到達するため、学校の業務 {@code @Transactional} の中から呼ぶと、学校の TX 入口が他ドメインの Repository へ
 * 越境する（D-3T）。さらに例外で拒否する判定が参加中の TX を rollback-only にしてしまう。
 * 認可は必ず Facade（{@code *AttendanceFacade}、非トランザクション）から行い、通過してから業務 Service（TX）を呼ぶこと
 * （先例: shift の {@code ShiftRequestFacade}）。業務 Service は本クラスを持たない（番人
 * {@code SchoolAttendanceAuthzGuardArchTest}）。</p>
 */
@Slf4j
@Service
public class SchoolAttendanceAccessPolicy {

    /** 出欠・学級担任情報の閲覧権限名（{@code permissions.name} の値）。権限グループ経由で委任される。 */
    static final String PERMISSION_VIEW_ATTENDANCE = "VIEW_ATTENDANCE";

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

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
                || canViewAsStaff(userId, teamId);
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

    /** 管理者以外の閲覧資格: 現役の担任・副担任、または {@code VIEW_ATTENDANCE} 委任者。 */
    private boolean canViewAsStaff(Long userId, Long teamId) {
        return isActiveHomeroomTeacher(userId, teamId)
                || accessControlService.hasPermission(userId, teamId, SCOPE_TEAM, PERMISSION_VIEW_ATTENDANCE);
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

    /** 登録 entries に同じ生徒が重複していれば {@link SchoolErrorCode#DUPLICATE_STUDENT_ENTRY}（400）。DB に触れない。 */
    public void requireNoDuplicateStudents(List<Long> studentUserIds) {
        Set<Long> seen = new HashSet<>();
        for (Long studentUserId : studentUserIds) {
            if (!seen.add(studentUserId)) {
                throw new BusinessException(SchoolErrorCode.DUPLICATE_STUDENT_ENTRY);
            }
        }
    }

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

    // 以下の check* は、既存の認可番人（AuthzControllerGuardArchTest: Controller から 2 ホップ）が
    // Facade → check* の経路で AccessControlService の呼び出しを検出できるよう、各メソッド本体で
    // AccessControlService を直接呼ぶ（別メソッドへ委譲しない）。

    /** V を要求する。権限なしは 403 COMMON_002。 */
    public void checkCanView(Long userId, Long teamId) {
        if (userId != null && teamId != null
                && (accessControlService.isAdminOrAbove(userId, teamId, SCOPE_TEAM)
                || canViewAsStaff(userId, teamId))) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    /** R を要求する。権限なしは 403 COMMON_002。 */
    public void checkCanRecordDaily(Long userId, Long teamId) {
        if (userId != null && teamId != null
                && (accessControlService.isAdminOrAbove(userId, teamId, SCOPE_TEAM)
                || isActiveHomeroomTeacher(userId, teamId))) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    /** P を要求する。権限なしは 403 COMMON_002。第1段は R と同じ判定（第2段で教科担当を差し込む）。 */
    public void checkCanRecordPeriod(Long userId, Long teamId) {
        if (userId != null && teamId != null
                && (accessControlService.isAdminOrAbove(userId, teamId, SCOPE_TEAM)
                || isActiveHomeroomTeacher(userId, teamId))) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    // ========================================
    // 生徒単位の閲覧（本人・保護者・教職員）
    // ========================================

    /**
     * 閲覧者が対象生徒本人、または対象生徒への ACTIVE な careLink を持つ保護者かを返す。
     *
     * <p>例外を投げない問い合わせ（{@code AccessControlService#hasActiveCareLink}）で判定する。
     * {@code checkCareLink} の例外を捕まえて「保護者ではない」に畳むと、例外が通過した TX を
     * rollback-only にして、後で正当な閲覧を許可しても 500 になる。</p>
     */
    public boolean isSelfOrGuardian(Long studentUserId, Long viewerUserId) {
        if (viewerUserId == null || studentUserId == null) {
            return false;
        }
        return viewerUserId.equals(studentUserId)
                || accessControlService.hasActiveCareLink(viewerUserId, studentUserId);
    }

    /**
     * 生徒単位の閲覧（AC-4）の範囲を解決する。
     *
     * @return 本人・保護者なら {@code null}（全クラス分を返してよい）。教職員なら、生徒が現在所属するクラスのうち
     *         閲覧者が閲覧権（V）を持つクラスの ID 集合
     * @throws BusinessException 本人でも保護者でも、閲覧権のあるクラスの教職員でもない場合（COMMON_002）
     */
    public Set<Long> resolveViewableTeamIds(Long studentUserId, Long viewerUserId) {
        if (isSelfOrGuardian(studentUserId, viewerUserId)) {
            return null;
        }
        Set<Long> viewable = new HashSet<>();
        if (studentUserId != null) {
            for (Long teamId : accessControlService
                    .findActiveMembershipJoinedAtByScope(studentUserId, SCOPE_TEAM).keySet()) {
                if (canView(viewerUserId, teamId)) {
                    viewable.add(teamId);
                }
            }
        }
        if (viewable.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return viewable;
    }

    /** 生徒が当該クラスの在籍メンバー（有効な membership）か。 */
    public boolean isEnrolledStudent(Long teamId, Long studentUserId) {
        if (teamId == null || studentUserId == null) {
            return false;
        }
        return accessControlService.listActiveMemberIds(teamId, SCOPE_TEAM).contains(studentUserId);
    }

    // ========================================
    // 出席要件規程の評価・解消（規程 entity 由来スコープ）
    // ========================================

    /**
     * 規程 entity 由来スコープの書込権限があるか。
     *
     * <p>組織スコープ規程（organizationId あり）は当該組織の ADMIN/DEPUTY_ADMIN のみ（AC-22）。
     * スコープ付きの資格だけを見るため、SYSTEM_ADMIN 単独には資格を与えず、SYSTEM_ADMIN と当該組織の
     * DEPUTY_ADMIN を兼任する利用者は許可する。組織の一般 MEMBER・チームの担任／管理者は含めない。
     * チームスコープ規程は日次登録権（R）を持つ担任・副担任・管理者のみ（AC-13）。
     * スコープが解決できない規程は権限なしとして扱う。</p>
     */
    public boolean canWriteRequirementRule(Long userId, Long organizationId, Long teamId) {
        if (userId == null) {
            return false;
        }
        if (organizationId != null) {
            return accessControlService.isAdminOrAbove(userId, organizationId, SCOPE_ORGANIZATION);
        }
        return teamId != null && canRecordDaily(userId, teamId);
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
