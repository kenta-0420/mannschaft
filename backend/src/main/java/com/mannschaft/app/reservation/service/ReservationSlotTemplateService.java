package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateSlotTemplateRequest;
import com.mannschaft.app.reservation.dto.DeleteSlotTemplateResponse;
import com.mannschaft.app.reservation.dto.GenerateSingleDayRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateListResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotTemplateRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 週間テンプレート CRUD サービス（F03.4.2 §5.1）。
 *
 * <p>重複/上限検証・IDOR 秘匿（404=RESERVATION_036）・generate のレートリミット＋委譲・監査ログを担う。
 * 生成本体は {@link ReservationSlotGenerationService}（単一実装）へ委譲する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationSlotTemplateService {

    /** F03.4.2 §3.2: 1 チームあたりのテンプレ行数上限（20ライン×7曜日×帯3本=420 を包含する切りの良い値）。 */
    static final long MAX_TEMPLATES_PER_TEAM = 500L;

    /** §6: generate のレートリミット（1 チーム 1 分間に 2 回まで）。 */
    private static final String GENERATE_RATE_ZONE = "reservation-template-generate";
    private static final int GENERATE_RATE_LIMIT = 2;
    private static final Duration GENERATE_RATE_WINDOW = Duration.ofMinutes(1);

    private final ReservationSlotTemplateRepository templateRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationSlotGenerationService generationService;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;
    private final AuditLogService auditLogService;
    private final ValkeyRateLimiter rateLimiter;

    /**
     * テンプレ一覧（曜日→開始時刻順）＋メタ（totalTemplates/limit）。
     *
     * <p>曜日順は enum 序数（MON..SUN）。DB の VARCHAR ソートはアルファベット順で意味を持たないため
     * Service 層で整列する。</p>
     */
    public SlotTemplateListResponse listTemplates(Long teamId) {
        List<ReservationSlotTemplateEntity> templates = templateRepository.findByTeamId(teamId).stream()
                .sorted(Comparator
                        .comparing((ReservationSlotTemplateEntity t) -> t.getDayOfWeek().ordinal())
                        .thenComparing(ReservationSlotTemplateEntity::getStartTime))
                .toList();
        Map<Long, String> lineNames = resolveLineNames(templates);
        List<SlotTemplateResponse> responses = templates.stream()
                .map(t -> toResponse(t, lineNames.get(t.getLineId())))
                .toList();
        return SlotTemplateListResponse.builder()
                .templates(responses)
                .meta(new SlotTemplateListResponse.TemplateListMetaDto(
                        templates.size(), (int) MAX_TEMPLATES_PER_TEAM))
                .build();
    }

    /**
     * テンプレ作成（§4 POST）。
     *
     * <p>検証: 上限 500 行（037）→ 時刻 007/022（既存コード再利用・単一検証点）→ lineId 001 →
     * staffUserId 所属 → 同一 (line, 曜日) の時間帯重複 007（共通枠同士は許可）。</p>
     */
    @Transactional
    public SlotTemplateResponse createTemplate(Long teamId, CreateSlotTemplateRequest request, Long createdBy) {
        if (templateRepository.countByTeamId(teamId) >= MAX_TEMPLATES_PER_TEAM) {
            throw new BusinessException(ReservationErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        SlotTimeValidator.validateTimeRange(request.getStartTime(), request.getEndTime(),
                Boolean.TRUE.equals(request.getEndsNextDay()));
        ReservationLineEntity line = resolveLineOrThrow(teamId, request.getLineId());
        validateStaffMembership(teamId, request.getStaffUserId());
        validateNoOverlap(teamId, request.getLineId(), request.getDayOfWeek(),
                request.getStartTime(), request.getEndTime(), Boolean.TRUE.equals(request.getEndsNextDay()), null);

        ReservationSlotTemplateEntity entity = ReservationSlotTemplateEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .lineId(request.getLineId())
                .dayOfWeek(request.getDayOfWeek())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .endsNextDay(Boolean.TRUE.equals(request.getEndsNextDay()))
                // 既定値（capacity=1）は Service 層で null→1 正規化（既存 normalizeCapacity と同じ考え方）。
                // builder に null を渡すと @Builder.Default を上書きして NULL 挿入になるため必ず正規化する。
                .capacity(normalizeCapacity(request.getCapacity()))
                .staffUserId(request.getStaffUserId())
                .title(request.getTitle())
                .price(request.getPrice())
                .approvalMode(request.getApprovalMode())
                .createdBy(createdBy)
                .build();
        ReservationSlotTemplateEntity saved = templateRepository.save(entity);
        log.info("週間テンプレート作成: teamId={}, templateId={}, dayOfWeek={}, lineId={}",
                teamId, saved.getId(), saved.getDayOfWeek(), saved.getLineId());
        recordAudit("RESERVATION_TEMPLATE_CREATED", createdBy, teamId, saved.getId());
        return toResponse(saved, line != null ? line.getName() : null);
    }

    /**
     * テンプレ部分更新（§4 PATCH・null=据え置き・clearLineId・isActive 切替）。
     *
     * <p><b>更新は既生成枠へ遡及しない</b>（§5.3/§5.4・F-8）: 予約が入っている可能性があり、
     * 黙って変えると予約者との合意を壊すため。変更後の初回生成から新定義が効く。</p>
     */
    @Transactional
    public SlotTemplateResponse updateTemplate(Long teamId, UUID templateId, UpdateSlotTemplateRequest request,
                                               Long updatedBy) {
        ReservationSlotTemplateEntity entity = findTemplateOrThrow(teamId, templateId);

        if (request.getName() != null) {
            entity.changeName(request.getName());
        }
        // lineId: clearLineId=true → 共通枠へ / 指定あり → 検証のうえ変更 / いずれも無し → 据え置き
        if (Boolean.TRUE.equals(request.getClearLineId())) {
            entity.clearLine();
        } else if (request.getLineId() != null) {
            resolveLineOrThrow(teamId, request.getLineId());
            entity.changeLine(request.getLineId());
        }
        if (request.getDayOfWeek() != null) {
            entity.changeDayOfWeek(request.getDayOfWeek());
        }
        if (request.getStartTime() != null || request.getEndTime() != null) {
            LocalTime newStart = request.getStartTime() != null ? request.getStartTime() : entity.getStartTime();
            LocalTime newEnd = request.getEndTime() != null ? request.getEndTime() : entity.getEndTime();
            boolean endsNextDay = request.getEndsNextDay() != null
                    ? request.getEndsNextDay() : Boolean.TRUE.equals(entity.getEndsNextDay());
            SlotTimeValidator.validateTimeRange(newStart, newEnd, endsNextDay);
            entity.changeTimeRange(newStart, newEnd, endsNextDay);
        } else if (request.getEndsNextDay() != null) {
            SlotTimeValidator.validateTimeRange(entity.getStartTime(), entity.getEndTime(), request.getEndsNextDay());
            entity.changeTimeRange(entity.getStartTime(), entity.getEndTime(), request.getEndsNextDay());
        }
        if (request.getCapacity() != null) {
            entity.changeCapacity(normalizeCapacity(request.getCapacity()));
        }
        if (request.getStaffUserId() != null) {
            validateStaffMembership(teamId, request.getStaffUserId());
            entity.changeStaffUser(request.getStaffUserId());
        }
        if (request.getTitle() != null) {
            entity.changeTitle(request.getTitle());
        }
        if (request.getPrice() != null) {
            entity.changePrice(request.getPrice());
        }
        if (request.getApprovalMode() != null) {
            entity.changeApprovalMode(request.getApprovalMode());
        }
        if (request.getIsActive() != null) {
            if (request.getIsActive()) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }
        // ライン・曜日・時間帯のいずれかが変わった可能性があるため、最終形で重複帯を再検証する（自分自身は除外）。
        validateNoOverlap(teamId, entity.getLineId(), entity.getDayOfWeek(),
                entity.getStartTime(), entity.getEndTime(), Boolean.TRUE.equals(entity.getEndsNextDay()), entity.getId());

        ReservationSlotTemplateEntity saved = templateRepository.save(entity);
        log.info("週間テンプレート更新: teamId={}, templateId={}", teamId, templateId);
        recordAudit("RESERVATION_TEMPLATE_UPDATED", updatedBy, teamId, templateId);
        return toResponse(saved, resolveLineName(saved.getLineId()));
    }

    /**
     * テンプレ物理削除（§4 DELETE）。
     *
     * <p>生成済み枠は FK {@code ON DELETE SET NULL} により {@code template_id=NULL} の通常枠として残る
     * （予約が入っている可能性があるため枠は消さない）。未来の未予約枠も自動削除しない
     * （枠削除は既存の枠 DELETE API で明示的に行う運用）。</p>
     */
    @Transactional
    public DeleteSlotTemplateResponse deleteTemplate(Long teamId, UUID templateId, Long deletedBy) {
        ReservationSlotTemplateEntity entity = findTemplateOrThrow(teamId, templateId);
        long orphanedSlotCount = slotRepository.countByTemplateId(templateId);
        templateRepository.delete(entity);
        log.info("週間テンプレート削除: teamId={}, templateId={}, orphanedSlotCount={}",
                teamId, templateId, orphanedSlotCount);
        recordAudit("RESERVATION_TEMPLATE_DELETED", deletedBy, teamId, templateId);
        return DeleteSlotTemplateResponse.builder()
                .id(templateId)
                .deleted(true)
                .orphanedSlotCount(orphanedSlotCount)
                .build();
    }

    /**
     * 一括生成（§4 generate）。
     *
     * <p>§6 資源保護: {@code ValkeyRateLimiter} で 1 チーム 1 分間に 2 回まで（超過は 429=RESERVATION_044）。
     * 生成本体（単一実装）へ委譲し、監査ログに件数付きで記録する。</p>
     *
     * <p>propagation=SUPPORTS: 生成は generation service 内の日付チャンク tx（REQUIRES_NEW）で行うため、
     * ここで読み取り専用 tx を張らない（クラス既定の readOnly tx が長時間コネクションを掴むのを避ける）。</p>
     *
     * @deprecated F03.4.5 §3.1: テンプレ保存＝同期自動生成の採用により「今すぐ枠を作成」UI は撤去された。
     *     本 API は生成型を参照する既存クライアント・E2E・運用リカバリ（バッチ障害時の手動追い付き）用に
     *     互換維持で残置する（OpenAPI {@code deprecated: true}）。削除は将来の別 PR・要裁可（§14）。
     */
    @Deprecated(since = "F03.4.5")
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public GenerateSlotsResponse generate(Long teamId, GenerateSlotsRequest request, Long userId) {
        RateLimitResult rate = rateLimiter.tryConsume(
                GENERATE_RATE_ZONE, "team:" + teamId, GENERATE_RATE_LIMIT, GENERATE_RATE_WINDOW);
        if (!rate.allowed()) {
            throw new BusinessException(ReservationErrorCode.TEMPLATE_GENERATE_RATE_LIMITED);
        }
        GenerateSlotsResponse response =
                generationService.generateForTeam(teamId, request != null ? request.getWeeks() : null, userId);
        recordAudit("RESERVATION_TEMPLATE_GENERATED", userId, teamId, null,
                String.format("{\"generated\":%d,\"skippedExisting\":%d,\"skippedClosedDay\":%d,"
                                + "\"skippedOutsideHours\":%d,\"horizonFrom\":\"%s\",\"horizonTo\":\"%s\"}",
                        response.getGeneratedCount(), response.getSkippedExistingCount(),
                        response.getSkippedClosedDayCount(), response.getSkippedOutsideHoursCount(),
                        response.getHorizonFrom(), response.getHorizonTo()));
        return response;
    }

    /**
     * テンプレ保存＝同期自動生成の外側ファサード（F03.4.5 §3.1）。
     *
     * <p><b>tx 境界の要（S-3b の番人）</b>: {@code propagation=SUPPORTS} で新規 tx を張らず
     * （保存 tx は呼び出し前に既にコミット済み）、生成本体（{@code ReservationSlotGenerationService}）の
     * 日付チャンク tx（REQUIRES_NEW）へ委譲する。<b>保存 tx の内側から呼んではならない</b>
     * （FK {@code fk_rs_template} の未コミット親行を参照して自己デッドロックする・§3.1 の⚠罠）。
     * コントローラは {@code createTemplate}/{@code updateTemplate}（{@code @Transactional}）の
     * 完了後に本メソッドを呼ぶ。</p>
     *
     * <p><b>レートリミット対象外</b>（§3.1）: 保存操作そのものが頻度の自然な上限で、単一テンプレ scope＋冪等の
     * ため暴走しない。無効化されたテンプレ（{@code isActive=false}）は生成対象外として空カウントを返す
     * （§3.1「無効化は以後生成されないだけ」）。</p>
     *
     * @param teamId     チームID
     * @param templateId 保存されたテンプレ ID
     * @param userId     実行者
     * @return 生成結果カウント
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public GenerateSlotsResponse generateForTemplate(Long teamId, UUID templateId, Long userId) {
        ReservationSlotTemplateEntity template = findTemplateOrThrow(teamId, templateId);
        if (!Boolean.TRUE.equals(template.getIsActive())) {
            // 無効テンプレは生成しない（空カウント・horizon 情報付き）。
            return generationService.generateForTemplates(teamId, List.of(), userId);
        }
        return generationService.generateForTemplate(teamId, template, userId);
    }

    /**
     * 営業時間 PUT の変更曜日差分生成の外側ファサード（F03.4.5 §3.2）。
     *
     * <p>指定曜日の active テンプレのみを対象に horizon 28 日を生成する（INSERT 量を全テンプレ方式の
     * 約 1/7〜2/7 に抑える）。tx 境界は {@link #generateForTemplate} と同一規約（SUPPORTS・保存 tx 外側）。</p>
     *
     * @param teamId チームID
     * @param days   変更のあった曜日集合（空なら生成なし）
     * @param userId 実行者
     * @return 生成結果カウント
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public GenerateSlotsResponse generateForDaysOfWeek(Long teamId, Set<ReservationDayOfWeek> days, Long userId) {
        if (days == null || days.isEmpty()) {
            return generationService.generateForTemplates(teamId, List.of(), userId);
        }
        List<ReservationSlotTemplateEntity> templates = templateRepository.findByTeamIdAndIsActiveTrue(teamId)
                .stream()
                .filter(t -> days.contains(t.getDayOfWeek()))
                .toList();
        return generationService.generateForTemplates(teamId, templates, userId);
    }

    /**
     * 臨時営業（単日テンプレ適用・F03.4.5 §3.3.2 generate-single-day）。
     *
     * <p>既存 generate と同一 zone のレートリミット（1 チーム 2 回/分・RESERVATION_044 再利用）を共有する
     * （同種の生成資源保護・別 zone にする理由がない）。生成本体（営業時間チェック省略・単日）へ委譲し、
     * 監査ログに記録する。tx 境界は generate と同一（SUPPORTS・チャンク tx は REQUIRES_NEW）。</p>
     *
     * @param teamId  チームID
     * @param request 臨時営業リクエスト（date・sourceDayOfWeek）
     * @param userId  実行者
     * @return 生成結果カウント
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public GenerateSlotsResponse generateSingleDay(Long teamId, GenerateSingleDayRequest request, Long userId) {
        RateLimitResult rate = rateLimiter.tryConsume(
                GENERATE_RATE_ZONE, "team:" + teamId, GENERATE_RATE_LIMIT, GENERATE_RATE_WINDOW);
        if (!rate.allowed()) {
            throw new BusinessException(ReservationErrorCode.TEMPLATE_GENERATE_RATE_LIMITED);
        }
        GenerateSlotsResponse response = generationService.generateSingleDay(
                teamId, request.getDate(), request.getSourceDayOfWeek(), userId);
        recordAudit("RESERVATION_TEMPLATE_SINGLE_DAY_GENERATED", userId, teamId, null,
                String.format("{\"date\":\"%s\",\"sourceDayOfWeek\":\"%s\",\"generated\":%d,"
                                + "\"skippedExisting\":%d}",
                        request.getDate(),
                        request.getSourceDayOfWeek() != null ? request.getSourceDayOfWeek().name() : "AUTO",
                        response.getGeneratedCount(), response.getSkippedExistingCount()));
        return response;
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ────────────────────────────────────────────────────────────

    private ReservationSlotTemplateEntity findTemplateOrThrow(Long teamId, UUID templateId) {
        // IDOR: 他チームの templateId も存在ごと 404（RESERVATION_036）で秘匿する（§6）。
        return templateRepository.findByIdAndTeamId(templateId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * lineId の検証（null = 共通枠テンプレで検証不要）。
     * 当該チームの active ライン以外（他チーム/不存在/無効）は 400（LINE_NOT_FOUND=001 再利用・§4）。
     */
    private ReservationLineEntity resolveLineOrThrow(Long teamId, Long lineId) {
        if (lineId == null) {
            return null;
        }
        ReservationLineEntity line = lineRepository.findByIdAndTeamId(lineId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.LINE_NOT_FOUND));
        if (!Boolean.TRUE.equals(line.getIsActive())) {
            throw new BusinessException(ReservationErrorCode.LINE_NOT_FOUND);
        }
        return line;
    }

    /** staffUserId のチーム所属チェック（親 §3 の所属チェックと同じ・任意項目のため null は素通し）。 */
    private void validateStaffMembership(Long teamId, Long staffUserId) {
        if (staffUserId == null) {
            return;
        }
        if (!accessControlService.isMember(staffUserId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "staffUserId", "指定された担当スタッフはこのチームに所属していません")));
        }
    }

    /**
     * 同一 (team, line_id, day_of_week) 内の時間帯重複検証（§3.2・400=007 再利用・メッセージで区別）。
     *
     * <p>重複テンプレは同一セルを二重生成しようとして UNIQUE 制約エラーになるため入口で止める。
     * <b>共通枠テンプレ（line_id NULL）同士の重複は許可</b>（既存の「共通枠同士の重複許可」と整合・親 §3）。</p>
     *
     * @param excludeId 更新時に自分自身を除外するテンプレ ID（作成時は null）
     */
    private void validateNoOverlap(Long teamId, Long lineId, ReservationDayOfWeek dayOfWeek,
                                   LocalTime startTime, LocalTime endTime, UUID excludeId) {
        validateNoOverlap(teamId, lineId, dayOfWeek, startTime, endTime, false, excludeId);
    }
    private void validateNoOverlap(Long teamId, Long lineId, ReservationDayOfWeek dayOfWeek,
                                   LocalTime startTime, LocalTime endTime, boolean endsNextDay, UUID excludeId) {
        if (lineId == null) {
            return; // 共通枠テンプレ同士は意図的な並行枠として許可
        }
        boolean overlaps = templateRepository.findByTeamId(teamId).stream()
                .filter(t -> !Objects.equals(t.getId(), excludeId))
                .filter(t -> Objects.equals(t.getLineId(), lineId))
                .filter(t -> t.getDayOfWeek() == dayOfWeek || (endsNextDay
                        && t.getDayOfWeek() == ReservationDayOfWeek.values()[(dayOfWeek.ordinal() + 1) % 7]))
                .anyMatch(t -> {
                    java.time.LocalDate anchor = java.time.LocalDate.of(2024, 1, 1);
                    java.time.LocalDate aDate = anchor.plusDays(dayOfWeek.ordinal());
                    java.time.LocalDate bDate = anchor.plusDays(t.getDayOfWeek().ordinal());
                    java.time.LocalDate bEnd = bDate.plusDays(Boolean.TRUE.equals(t.getEndsNextDay()) ? 1 : 0);
                    return java.time.LocalDateTime.of(aDate, startTime).isBefore(java.time.LocalDateTime.of(bEnd, t.getEndTime()))
                            && java.time.LocalDateTime.of(bDate, t.getStartTime()).isBefore(
                            java.time.LocalDateTime.of(aDate.plusDays(endsNextDay ? 1 : 0), endTime));
                });
        if (overlaps) {
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE,
                    List.of(new ErrorResponse.FieldError(
                            "startTime", "同一ライン・同一曜日の既存テンプレートと時間帯が重複しています")));
        }
    }

    private Integer normalizeCapacity(Integer capacity) {
        if (capacity == null || capacity < 1) {
            return 1;
        }
        return capacity;
    }

    private Map<Long, String> resolveLineNames(List<ReservationSlotTemplateEntity> templates) {
        List<Long> lineIds = templates.stream()
                .map(ReservationSlotTemplateEntity::getLineId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> names = new HashMap<>();
        if (!lineIds.isEmpty()) {
            for (ReservationLineEntity line : lineRepository.findAllById(lineIds)) {
                names.put(line.getId(), line.getName());
            }
        }
        return names;
    }

    private String resolveLineName(Long lineId) {
        if (lineId == null) {
            return null;
        }
        return lineRepository.findById(lineId).map(ReservationLineEntity::getName).orElse(null);
    }

    private SlotTemplateResponse toResponse(ReservationSlotTemplateEntity entity, String lineName) {
        return SlotTemplateResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .lineId(entity.getLineId())
                .lineName(lineName)
                .dayOfWeek(entity.getDayOfWeek() != null ? entity.getDayOfWeek().name() : null)
                .startTime(entity.getStartTime())
                .endTime(entity.getEndTime())
                .endsNextDay(entity.getEndsNextDay())
                .capacity(entity.getCapacity())
                .staffUserId(entity.getStaffUserId())
                // staffName は管理画面のみで使う表示名（PII 考慮・§6）。null 安全に一括解決系と同じ Resolver を使う。
                .staffName(entity.getStaffUserId() != null
                        ? nameResolverService.resolveUserFullName(entity.getStaffUserId()) : null)
                .title(entity.getTitle())
                .price(entity.getPrice())
                .approvalMode(entity.getApprovalMode() != null ? entity.getApprovalMode().name() : null)
                .isActive(entity.getIsActive())
                .cellCount(entity.cellCount())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void recordAudit(String eventType, Long userId, Long teamId, UUID templateId) {
        recordAudit(eventType, userId, teamId, templateId, null);
    }

    /** 監査ログ（§6）: テンプレの作成・更新・削除・generate 実行（件数付き）を audit_logs に記録する。 */
    private void recordAudit(String eventType, Long userId, Long teamId, UUID templateId, String metadata) {
        String meta = metadata;
        if (meta == null && templateId != null) {
            meta = "{\"templateId\":\"" + templateId + "\"}";
        }
        auditLogService.record(eventType, userId, null, teamId, null, null, null, null, meta);
    }
}
