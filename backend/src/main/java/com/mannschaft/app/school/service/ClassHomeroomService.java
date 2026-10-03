package com.mannschaft.app.school.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.dto.ClassHomeroomCreateRequest;
import com.mannschaft.app.school.dto.ClassHomeroomResponse;
import com.mannschaft.app.school.dto.ClassHomeroomUpdateRequest;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/** 学級担任設定サービス。学級担任の CRUD を提供する。 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ClassHomeroomService {

    /**
     * 出欠・学級担任情報の閲覧権限名（{@code permissions.name} の値）。
     * ADMIN は既定で保有し、それ以外へは権限グループ経由で個別に委任する。
     */
    static final String PERMISSION_VIEW_ATTENDANCE = "VIEW_ATTENDANCE";

    private final ClassHomeroomRepository classHomeroomRepository;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    // ========================================
    // 学級担任設定 CRUD
    // ========================================

    /**
     * 指定チームの学級担任設定一覧を取得する。
     *
     * <p>認可: 以下のいずれかを満たす者のみ（それ以外は COMMON_002）。</p>
     * <ul>
     *   <li><b>チーム管理者</b>（{@code isAdminOrAbove}）— 全件を閲覧できる。</li>
     *   <li><b>{@code VIEW_ATTENDANCE} 権限保持者</b>（権限グループ経由の委任）— 全件を閲覧できる。</li>
     *   <li><b>当該チームの担任本人</b> — <b>自分が担任の学級のみ</b>閲覧できる。</li>
     * </ul>
     *
     * <p>ここでは認可と絞り込みを<b>二重に</b>担保する。すなわち
     * (1) 特権も担任実績も無い者は結果を組み立てる前に拒否し、
     * (2) 特権を持たない担任には自分が担任の行だけを返す。
     * 絞り込みだけに頼ると「0 件の一覧」が権限判定の代わりになってしまい、
     * 他人の担任設定が見えない保証が絞り込みロジックの正しさに依存してしまうため。</p>
     */
    public List<ClassHomeroomResponse> listHomerooms(Long teamId, Integer academicYear, Long currentUserId) {
        // (1) 認可ゲート: 特権保持者か、当該チームの担任本人か。
        boolean privileged = hasHomeroomViewPrivilege(currentUserId, teamId);
        if (!privileged && !isHomeroomTeacherOfTeam(currentUserId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        List<ClassHomeroomEntity> entities =
                classHomeroomRepository.findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(teamId, academicYear);
        return entities.stream()
                // (2) 絞り込み: 特権が無い担任本人には自分の担任分だけを返す。
                .filter(e -> privileged || isOwnHomeroom(e, currentUserId))
                .map(e -> ClassHomeroomResponse.from(e, parseAssistantIds(e.getAssistantTeacherUserIds())))
                .toList();
    }

    /**
     * 学級担任設定を登録する。
     * 認可: ORG_ADMIN のみ（チームスコープの ADMIN）。
     */
    @Transactional
    public ClassHomeroomResponse createHomeroom(Long teamId, ClassHomeroomCreateRequest request, Long currentUserId) {
        checkAdminForTeam(currentUserId, teamId);

        if (classHomeroomRepository.existsByTeamIdAndAcademicYearAndEffectiveUntilIsNull(teamId, request.getAcademicYear())) {
            throw new BusinessException(SchoolErrorCode.HOMEROOM_ALREADY_EXISTS);
        }

        ClassHomeroomEntity entity = ClassHomeroomEntity.builder()
                .teamId(teamId)
                .homeroomTeacherUserId(request.getHomeroomTeacherUserId())
                .assistantTeacherUserIds(serializeAssistantIds(request.getAssistantTeacherUserIds()))
                .academicYear(request.getAcademicYear())
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveUntil(request.getEffectiveUntil())
                .createdBy(currentUserId)
                .build();
        classHomeroomRepository.save(entity);
        return ClassHomeroomResponse.from(entity, request.getAssistantTeacherUserIds());
    }

    /**
     * 学級担任設定を更新する。
     * 認可: ORG_ADMIN のみ。
     */
    @Transactional
    public ClassHomeroomResponse updateHomeroom(Long teamId, Long homeroomId, ClassHomeroomUpdateRequest request, Long currentUserId) {
        checkAdminForTeam(currentUserId, teamId);

        ClassHomeroomEntity entity = classHomeroomRepository.findById(homeroomId)
                .filter(e -> e.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.HOMEROOM_NOT_FOUND));

        // toBuilder().build() で作り直すと BaseEntity.id が引き継がれず INSERT 化する（行重複）。
        // managed entity を直接ミューテートし JPA dirty checking で UPDATE する。
        String serializedAssistants = request.getAssistantTeacherUserIds() != null
                ? serializeAssistantIds(request.getAssistantTeacherUserIds())
                : null;
        entity.applyUpdate(request.getHomeroomTeacherUserId(), serializedAssistants, request.getEffectiveUntil());
        classHomeroomRepository.save(entity);
        return ClassHomeroomResponse.from(entity, parseAssistantIds(entity.getAssistantTeacherUserIds()));
    }

    // ========================================
    // 内部ユーティリティ
    // ========================================

    private void checkAdminForTeam(Long userId, Long teamId) {
        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 学級担任設定を「全件」閲覧できる特権を持つかを判定する。
     *
     * <p>チーム管理者（ADMIN 以上）、または {@code VIEW_ATTENDANCE} 権限の保持者。
     * 後者はロールに紐づく既定権限ではなく、権限グループ経由で個別に委任された者を指す。
     * 本メソッドは<b>メンバーシップを見ない</b>（見ているのはロールと権限のみ）ため、
     * メンバーであることを含意する名前を付けてはならない。</p>
     */
    private boolean hasHomeroomViewPrivilege(Long userId, Long teamId) {
        if (accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            return true;
        }
        return accessControlService.hasPermission(userId, teamId, "TEAM", PERMISSION_VIEW_ATTENDANCE);
    }

    /** 当該チームで 1 件でも担任を務めている（＝担任本人である）かを判定する。 */
    private boolean isHomeroomTeacherOfTeam(Long userId, Long teamId) {
        if (userId == null) {
            return false;
        }
        return classHomeroomRepository.existsByTeamIdAndHomeroomTeacherUserId(teamId, userId);
    }

    /** 当該担任設定の担任本人かを判定する。 */
    private boolean isOwnHomeroom(ClassHomeroomEntity entity, Long userId) {
        return userId != null && userId.equals(entity.getHomeroomTeacherUserId());
    }

    private String serializeAssistantIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("副担任リストのシリアライズに失敗しました", e);
        }
    }

    private List<Long> parseAssistantIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}
