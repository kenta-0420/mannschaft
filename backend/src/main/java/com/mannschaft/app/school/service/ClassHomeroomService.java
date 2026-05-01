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

    private final ClassHomeroomRepository classHomeroomRepository;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    // ========================================
    // 学級担任設定 CRUD
    // ========================================

    /**
     * 指定チームの学級担任設定一覧を取得する。
     * 認可: ADMIN または担任本人。
     */
    public List<ClassHomeroomResponse> listHomerooms(Long teamId, Integer academicYear, Long currentUserId) {
        checkAdminOrTeamMember(currentUserId, teamId);
        List<ClassHomeroomEntity> entities =
                classHomeroomRepository.findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(teamId, academicYear);
        return entities.stream()
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

        ClassHomeroomEntity updated = entity.toBuilder()
                .homeroomTeacherUserId(
                        request.getHomeroomTeacherUserId() != null
                                ? request.getHomeroomTeacherUserId()
                                : entity.getHomeroomTeacherUserId())
                .assistantTeacherUserIds(
                        request.getAssistantTeacherUserIds() != null
                                ? serializeAssistantIds(request.getAssistantTeacherUserIds())
                                : entity.getAssistantTeacherUserIds())
                .effectiveUntil(
                        request.getEffectiveUntil() != null
                                ? request.getEffectiveUntil()
                                : entity.getEffectiveUntil())
                .build();
        classHomeroomRepository.save(updated);
        return ClassHomeroomResponse.from(updated, parseAssistantIds(updated.getAssistantTeacherUserIds()));
    }

    // ========================================
    // 内部ユーティリティ
    // ========================================

    private void checkAdminForTeam(Long userId, Long teamId) {
        if (!accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private void checkAdminOrTeamMember(Long userId, Long teamId) {
        // チームメンバーシップ確認は AccessControlService に委譲
        // COMMON_002 = 権限不足
        try {
            accessControlService.checkPermission(userId, teamId, "TEAM", "VIEW_ATTENDANCE");
        } catch (BusinessException e) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
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
