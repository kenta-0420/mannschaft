package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.schedule.ScheduleKeepErrorCode;
import com.mannschaft.app.schedule.authz.ScheduleKeepAccessGuard;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.dto.CreateScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ScheduleKeepResponse;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * キープ（日付未定の予定）の CRUD サービス（F03.17 第三陣・Wave1）。
 *
 * <p>認可は必ず {@link ScheduleKeepAccessGuard} を通す（独自の認可判定を書かない）。
 * 変換・アーカイブ解除後の予定側の後始末（revert の変換先削除等）は本サービスの範囲外
 * （convert 自体が Wave1 時点で未実装のため、{@code convertedScheduleId} は常に {@code null} であり
 * {@code archive}/{@code restore}/{@code revert} は「キープ自身の状態」のみを扱う）。</p>
 *
 * <p>設計: {@code docs/features/F03.17_schedule_keep.md} §4 / §5 / §7。</p>
 */
@Service
@RequiredArgsConstructor
public class ScheduleKeepService {

    private static final int MAX_CANDIDATE_DATES = 10;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** gap 採番（§10.2）。新規作成時の sort_order は常に先頭になるよう既存最小値より小さくする運用は取らず、
     * 既定 0（未整列時は created_at 降順で「新しい順」になる・§10.2）。 */
    private static final int DEFAULT_SORT_ORDER = 0;

    private final ScheduleKeepRepository scheduleKeepRepository;
    private final ScheduleKeepAccessGuard scheduleKeepAccessGuard;
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final NameResolverService nameResolverService;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 作成
    // ------------------------------------------------------------------

    @Transactional
    public ScheduleKeepResponse create(ScheduleKeepScope scope, CreateScheduleKeepRequest request, Long viewerUserId) {
        scheduleKeepAccessGuard.requireScopeAccess(scope, viewerUserId);

        String title = validateAndNormalizeTitle(request.getTitle());
        List<LocalDate> candidateDates = validateAndNormalizeCandidateDates(request.getCandidateDates());

        ScheduleKeepEntity.ScheduleKeepEntityBuilder<?, ?> builder = ScheduleKeepEntity.builder()
                .title(title)
                .memo(request.getMemo())
                .candidateDates(toJson(candidateDates))
                .status(ScheduleKeepStatus.KEPT)
                .sortOrder(DEFAULT_SORT_ORDER)
                .createdBy(viewerUserId);
        applyScope(builder, scope);

        ScheduleKeepEntity saved = scheduleKeepRepository.save(builder.build());
        return toResponse(saved, scope);
    }

    // ------------------------------------------------------------------
    // 一覧
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ScheduleKeepResponse> list(ScheduleKeepScope scope, String statusParam, int page, int size,
                                            Long viewerUserId) {
        scheduleKeepAccessGuard.requireScopeAccess(scope, viewerUserId);

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize,
                Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.desc("createdAt")));

        String normalizedStatus = statusParam == null ? "KEPT" : statusParam.toUpperCase();
        Page<ScheduleKeepEntity> results = fetchByStatus(scope, normalizedStatus, pageable);

        return results.stream().map(entity -> toResponse(entity, scope)).toList();
    }

    private Page<ScheduleKeepEntity> fetchByStatus(ScheduleKeepScope scope, String statusParam, Pageable pageable) {
        if ("ALL".equals(statusParam)) {
            return switch (scope.type()) {
                case TEAM -> scheduleKeepRepository.findByTeamId(scope.id(), pageable);
                case ORGANIZATION -> scheduleKeepRepository.findByOrganizationId(scope.id(), pageable);
                case PERSONAL -> scheduleKeepRepository.findByUserId(scope.id(), pageable);
            };
        }
        ScheduleKeepStatus status = parseStatus(statusParam);
        return switch (scope.type()) {
            case TEAM -> scheduleKeepRepository.findByTeamIdAndStatus(scope.id(), status, pageable);
            case ORGANIZATION -> scheduleKeepRepository.findByOrganizationIdAndStatus(scope.id(), status, pageable);
            case PERSONAL -> scheduleKeepRepository.findByUserIdAndStatus(scope.id(), status, pageable);
        };
    }

    private ScheduleKeepStatus parseStatus(String statusParam) {
        try {
            return ScheduleKeepStatus.valueOf(statusParam);
        } catch (IllegalArgumentException e) {
            return ScheduleKeepStatus.KEPT;
        }
    }

    // ------------------------------------------------------------------
    // 単体取得
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ScheduleKeepResponse get(ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireViewable(scope, keepId, viewerUserId);
        return toResponse(keep, scope);
    }

    // ------------------------------------------------------------------
    // 更新（PATCH。未指定キー=変更なし、明示的null=クリア・§4.4）
    // ------------------------------------------------------------------

    @Transactional
    public ScheduleKeepResponse update(ScheduleKeepScope scope, UUID keepId, Map<String, Object> body,
                                        Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireEditable(scope, keepId, viewerUserId);

        if (keep.getStatus() == ScheduleKeepStatus.ARCHIVED) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_NOT_EDITABLE);
        }
        boolean editingLockedFields = body.containsKey("title") || body.containsKey("candidateDates");
        if (keep.getStatus() == ScheduleKeepStatus.SCHEDULED && editingLockedFields) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_NOT_EDITABLE);
        }

        if (body.containsKey("title")) {
            Object rawTitle = body.get("title");
            keep.setTitle(validateAndNormalizeTitle(rawTitle == null ? null : rawTitle.toString()));
        }
        if (body.containsKey("memo")) {
            Object rawMemo = body.get("memo");
            keep.setMemo(rawMemo == null ? null : rawMemo.toString());
        }
        if (body.containsKey("candidateDates")) {
            @SuppressWarnings("unchecked")
            List<String> rawDates = (List<String>) body.get("candidateDates");
            keep.setCandidateDates(toJson(validateAndNormalizeCandidateDates(rawDates)));
        }

        ScheduleKeepEntity saved = scheduleKeepRepository.save(keep);
        return toResponse(saved, scope);
    }

    // ------------------------------------------------------------------
    // 削除（論理削除）
    // ------------------------------------------------------------------

    @Transactional
    public void delete(ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireEditable(scope, keepId, viewerUserId);
        keep.setDeletedAt(java.time.LocalDateTime.now());
        scheduleKeepRepository.save(keep);
    }

    // ------------------------------------------------------------------
    // archive / restore / revert
    //
    // Wave1 時点では convert が未実装であり converted_schedule_id は常に null のため、
    // 本サービスは「キープ自身の状態」のみを扱う（変換先 schedules の後始末は convert 実装と
    // 併せて Wave4 が拡張する。§5.3 の全セル表のうち到達可能なセルのみを実装する）。
    // ------------------------------------------------------------------

    @Transactional
    public ScheduleKeepResponse archive(ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireEditable(scope, keepId, viewerUserId);
        // ARCHIVED への archive は冪等 no-op（§5.3）。
        keep.setStatus(ScheduleKeepStatus.ARCHIVED);
        ScheduleKeepEntity saved = scheduleKeepRepository.save(keep);
        return toResponse(saved, scope);
    }

    @Transactional
    public ScheduleKeepResponse restore(ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireEditable(scope, keepId, viewerUserId);
        if (keep.getStatus() == ScheduleKeepStatus.ARCHIVED) {
            // 戻り先は由来による: conv_id が NULL なら KEPT、非 NULL なら SCHEDULED（§5.3.1）。
            keep.setStatus(keep.getConvertedScheduleId() == null
                    ? ScheduleKeepStatus.KEPT
                    : ScheduleKeepStatus.SCHEDULED);
        }
        // KEPT/SCHEDULED への restore は冪等 no-op（§5.3）。
        ScheduleKeepEntity saved = scheduleKeepRepository.save(keep);
        return toResponse(saved, scope);
    }

    @Transactional
    public ScheduleKeepResponse revert(ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireEditable(scope, keepId, viewerUserId);
        if (keep.getConvertedScheduleId() == null) {
            // 取り消す対象が無い（§5.3・SCHEDULE_KEEP_009）。
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_STATE_TRANSITION);
        }
        // conv_id が非 null の revert（変換先 schedules の後始末含む）は convert 実装と併せて
        // Wave4 が拡張する。Wave1〜3 時点では convert が存在せず conv_id は常に null のため
        // このブランチには到達しない。
        keep.setStatus(ScheduleKeepStatus.KEPT);
        keep.setConvertedScheduleId(null);
        ScheduleKeepEntity saved = scheduleKeepRepository.save(keep);
        return toResponse(saved, scope);
    }

    // ------------------------------------------------------------------
    // バリデーション
    // ------------------------------------------------------------------

    private String validateAndNormalizeTitle(String title) {
        if (title == null || title.trim().isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_TITLE_REQUIRED);
        }
        return title;
    }

    private List<LocalDate> validateAndNormalizeCandidateDates(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<LocalDate> parsed = new ArrayList<>();
        for (String s : raw) {
            try {
                parsed.add(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (DateTimeParseException | NullPointerException e) {
                throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_CANDIDATE_DATE);
            }
        }
        if (raw.size() > MAX_CANDIDATE_DATES) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_TOO_MANY_CANDIDATE_DATES);
        }
        List<LocalDate> distinctSorted = parsed.stream().distinct().sorted().toList();
        return distinctSorted.isEmpty() ? null : distinctSorted;
    }

    // ------------------------------------------------------------------
    // スコープ・変換ヘルパー
    // ------------------------------------------------------------------

    private void applyScope(ScheduleKeepEntity.ScheduleKeepEntityBuilder<?, ?> builder, ScheduleKeepScope scope) {
        switch (scope.type()) {
            case TEAM -> builder.teamId(scope.id());
            case ORGANIZATION -> builder.organizationId(scope.id());
            case PERSONAL -> builder.userId(scope.id());
        }
    }

    private String toJson(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return null;
        }
        List<String> asStrings = dates.stream().map(LocalDate::toString).toList();
        try {
            return objectMapper.writeValueAsString(asStrings);
        } catch (Exception e) {
            throw new IllegalStateException("候補日のJSONシリアライズに失敗しました", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> dates = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return dates.isEmpty() ? null : dates;
        } catch (Exception e) {
            throw new IllegalStateException("候補日のJSONデシリアライズに失敗しました", e);
        }
    }

    private ScheduleKeepResponse toResponse(ScheduleKeepEntity entity, ScheduleKeepScope scope) {
        String teamPublicId = null;
        String organizationPublicId = null;
        String scopeType;
        switch (scope.type()) {
            case TEAM -> {
                scopeType = "TEAM";
                teamPublicId = teamService.getSlugById(entity.getTeamId());
            }
            case ORGANIZATION -> {
                scopeType = "ORGANIZATION";
                organizationPublicId = organizationService.getSlugById(entity.getOrganizationId());
            }
            default -> scopeType = "PERSONAL";
        }

        ScheduleKeepResponse.CreatedByDto createdBy = null;
        if (entity.getCreatedBy() != null) {
            String displayName = nameResolverService.resolveUserDisplayName(entity.getCreatedBy());
            createdBy = ScheduleKeepResponse.CreatedByDto.builder()
                    .userId(entity.getCreatedBy())
                    .displayName(displayName)
                    .build();
        }

        return ScheduleKeepResponse.builder()
                .id(entity.getId().toString())
                .scopeType(scopeType)
                .teamPublicId(teamPublicId)
                .organizationPublicId(organizationPublicId)
                .title(entity.getTitle())
                .memo(entity.getMemo())
                .candidateDates(fromJson(entity.getCandidateDates()))
                .status(entity.getStatus().name())
                .convertedScheduleId(entity.getConvertedScheduleId())
                .convertedScheduleState(entity.getConvertedScheduleId() == null ? "NONE" : "ACTIVE")
                .sortOrder(entity.getSortOrder())
                .createdBy(createdBy)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

}
