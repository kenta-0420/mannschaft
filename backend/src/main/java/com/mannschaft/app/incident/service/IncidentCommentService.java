package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.entity.IncidentCommentEntity;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.repository.IncidentAssignmentRepository;
import com.mannschaft.app.incident.repository.IncidentCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** インシデントコメントの参照を提供するサービス。 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class IncidentCommentService {

    private static final String UNKNOWN_USER_DISPLAY_NAME = "不明なユーザー";

    private final IncidentService incidentService;
    private final IncidentCommentRepository commentRepository;
    private final IncidentAssignmentRepository assignmentRepository;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    /**
     * コメント一覧を作成日時の昇順で返す。
     *
     * <p>URL に scope が含まれないため、先に親 incident を取得し、その entity が持つ scope で
     * 所属と可視性を検証する。非所属または閲覧対象外の場合は、存在を秘匿して incident と同じ
     * {@link IncidentErrorCode#INCIDENT_002}（404）を返す。</p>
     *
     * <p>内部コメントは管理者だけが取得できる。{@code includeInternal} を repository の SQL 条件へ
     * 渡し、非管理者の結果セットに内部コメントを含めない。</p>
     */
    public List<IncidentCommentResponse> listComments(Long incidentId, Long userId) {
        IncidentEntity incident = incidentService.findIncidentOrThrow(incidentId);
        boolean includeInternal = requireVisibleOrConceal(incident, userId);
        List<IncidentCommentEntity> comments = commentRepository
                .findVisibleByIncidentIdOrderByCreatedAtAsc(incidentId, includeInternal);

        Set<Long> authorIds = comments.stream()
                .map(IncidentCommentEntity::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String> displayNames = nameResolverService.resolveUserFullNames(authorIds);

        return comments.stream()
                .map(comment -> IncidentCommentResponse.from(
                        comment,
                        displayNames.getOrDefault(comment.getUserId(), UNKNOWN_USER_DISPLAY_NAME)))
                .toList();
    }

    private boolean requireVisibleOrConceal(IncidentEntity incident, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (!accessControlService.isMember(userId, incident.getScopeId(), incident.getScopeType())) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_002);
        }
        if (accessControlService.isAdminOrAbove(userId, incident.getScopeId(), incident.getScopeType())) {
            return true;
        }
        if (accessControlService.isSupporter(userId, incident.getScopeId(), incident.getScopeType())) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_002);
        }
        boolean isReporter = userId.equals(incident.getReportedBy());
        boolean isAssignee = assignmentRepository
                .existsByIncidentIdAndUserIdAndAssigneeType(incident.getId(), userId, "USER");
        if (!isReporter && !isAssignee) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_002);
        }
        return false;
    }

    /** コメント一覧の API レスポンス。添付ファイルは AWS 連携実装まで返さない。 */
    public record IncidentCommentResponse(
            Long id,
            Long incidentId,
            Long userId,
            IncidentCommentUserResponse user,
            String body,
            boolean isInternal,
            LocalDateTime createdAt) {

        static IncidentCommentResponse from(IncidentCommentEntity entity, String userDisplayName) {
            return new IncidentCommentResponse(
                    entity.getId(),
                    entity.getIncidentId(),
                    entity.getUserId(),
                    new IncidentCommentUserResponse(entity.getUserId(), userDisplayName),
                    entity.getBody(),
                    Boolean.TRUE.equals(entity.getIsInternal()),
                    entity.getCreatedAt());
        }
    }

    /** コメント投稿者の表示情報。保存済み userId は退会後も監査用に保持する。 */
    public record IncidentCommentUserResponse(Long id, String displayName) {}
}
