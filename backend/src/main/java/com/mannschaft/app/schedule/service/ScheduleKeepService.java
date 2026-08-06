package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleKeepErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.authz.ScheduleKeepAccessGuard;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ScheduleKeepResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * キープ（日付未定の予定）の CRUD サービス（F03.17 第三陣・Wave1）。
 *
 * <p>認可は必ず {@link ScheduleKeepAccessGuard} を通す（独自の認可判定を書かない）。
 * Wave2 で変換（convert）・逆引き（by-schedule）・並び替え（reorder）と、
 * §5.3 の状態 × 操作の全セルを実装した。{@code revert} は変換先 {@code schedules} の
 * 論理削除まで責任を持つ（キープ側だけ巻き戻して予定を残すと、カレンダーに孤児が残るため）。</p>
 *
 * <p><b>ドメイン境界</b>: 本サービスはキープと {@code schedules} の両方を1トランザクションで扱うが、
 * どちらも schedule ドメイン内であり越境していない（原則5）。通知（notification ドメイン）だけは
 * 越境するため、変換を巻き戻さないよう try/catch + ログで隔離する（{@link #notifyConverted}）。</p>
 *
 * <p>設計: {@code docs/features/F03.17_schedule_keep.md} §4 / §5 / §7 / §10。</p>
 */
@Slf4j
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

    /** reorder の再採番幅（§10.2）。1件だけ間に差し込むときに後続全件の UPDATE を要らなくする。 */
    private static final int SORT_ORDER_GAP = 10;

    /** 個人スコープの {@code KEPT} 件数上限（§10.1）。 */
    private static final long MAX_KEPT_PERSONAL = 200L;
    /** チーム／組織スコープの {@code KEPT} 件数上限（§10.1）。 */
    private static final long MAX_KEPT_SHARED = 300L;

    private final ScheduleKeepRepository scheduleKeepRepository;
    private final ScheduleKeepAccessGuard scheduleKeepAccessGuard;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleAttendanceRepository scheduleAttendanceRepository;
    private final ScheduleKeepNotificationService scheduleKeepNotificationService;
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
        requireCapacityAvailable(scope);

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

        // 変換先の生存状態はページ内の全件分を 1 クエリで解決する（§4.5.1・N+1 回避）。
        // 1件ずつ引くと SCHEDULED が並ぶ一覧でそのままページサイズ分の SELECT になる。
        List<ScheduleKeepEntity> entities = results.getContent();
        Map<Long, String> stateByScheduleId = resolveConvertedScheduleStates(entities);

        return entities.stream().map(entity -> toResponse(entity, scope, stateByScheduleId)).toList();
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
    // 逆引き（この予定はどのキープから生まれたか・§4.5.1）
    // ------------------------------------------------------------------

    /**
     * 変換先の予定 ID から由来キープを引く（{@code GET .../by-schedule/{scheduleId}}）。
     *
     * <p>「キープ由来でない予定」と「他スコープの予定」はどちらも 404 に畳む（存在秘匿）。
     * 繰り返し展開された子の予定は誰の {@code converted_schedule_id} でもないため自然に 404 になる
     * （逆引きが指すのは常に変換で作られた親のみ・§4.5.1）。</p>
     */
    @Transactional(readOnly = true)
    public ScheduleKeepResponse getByConvertedSchedule(ScheduleKeepScope scope, Long scheduleId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard
                .requireViewableByConvertedSchedule(scope, scheduleId, viewerUserId);
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
    // 変換（convert・§4.5）
    // ------------------------------------------------------------------

    /**
     * キープをカレンダーの予定へ変換する（「2タップ以内で日程化」の実体）。
     *
     * <p><b>権限は MEMBER 全員に開放する</b>（{@code requireConvertible}・§2.1.1）。
     * 編集系ゲートを流用すると「言い出しっぺ以外は日程を入れられない」退行になり、本機能の価値が消える。
     * その代償として<b>キープ作成者への通知は必須</b>である（§6.1）。</p>
     *
     * <p><b>二重生成の防止</b>: {@code SCHEDULED} への再変換をここで 409 に落とすのが唯一の防止機構である
     * （§5.3.1）。{@code archive} → {@code restore} が {@code SCHEDULED}（{@code conv_id} 保持）へ戻るのは、
     * 巻き戻し経路をこの一箇所の判定に必ず合流させるためであり、意図的な設計である。</p>
     */
    @Transactional
    public ConvertScheduleKeepResponse convert(ScheduleKeepScope scope, UUID keepId,
                                                ConvertScheduleKeepRequest request, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireConvertible(scope, keepId, viewerUserId);

        // §5.3 の全セル表。ARCHIVED は由来（conv_id の有無）を問わず _009、SCHEDULED は _006。
        // ARCHIVED を _006 にしないのは「変換できない理由が違う」から（前者は状態が不正、
        // 後者は既に変換済み）。FE が出す案内文（restore せよ／既に予定がある）が変わる。
        switch (keep.getStatus()) {
            case SCHEDULED -> throw new BusinessException(ScheduleKeepErrorCode.KEEP_NOT_CONVERTIBLE);
            case ARCHIVED -> throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_STATE_TRANSITION);
            default -> { /* KEPT のみ変換できる */ }
        }

        if (request == null || request.getStartAt() == null) {
            // schedules.start_at が NOT NULL のため startAt は必須（§4.5）。
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        boolean allDay = request.getAllDay() == null || request.getAllDay();
        LocalDateTime startAt = allDay
                ? request.getStartAt().toLocalDate().atStartOfDay()
                : request.getStartAt();

        ScheduleEntity schedule = ScheduleEntity.builder()
                .title(keep.getTitle())
                .description(keep.getMemo())
                // スコープは変換で変わらない（キープと予定が必ず同じシャードに落ちる前提・§10.3）。
                .teamId(keep.getTeamId())
                .organizationId(keep.getOrganizationId())
                .userId(keep.getUserId())
                .startAt(startAt)
                .endAt(request.getEndAt())
                .allDay(allDay)
                .eventType(EventType.OTHER)
                .status(ScheduleStatus.SCHEDULED)
                // 日程が決まっただけの段階で出欠を強制しない（§1.3「急かさない」）。
                .attendanceRequired(false)
                // ⚠️ 可視性は固定値で書く（§4.5.2.1）。スコープ既定を継承させると、既定が緩い
                // チームでは「変換した瞬間に応援者・ゲストへ見える予定になる」＝可視性が変換で緩む。
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                // 予定を作ったのは変換した人。キープの作成者は逆引きで辿れる（§4.5.2）。
                .createdBy(viewerUserId)
                .build();
        // 直後の通知で F00 可視性判定が SQL で予定を引くため、ここで flush して可視にする。
        ScheduleEntity savedSchedule = scheduleRepository.saveAndFlush(schedule);

        keep.setStatus(ScheduleKeepStatus.SCHEDULED);
        keep.setConvertedScheduleId(savedSchedule.getId());
        ScheduleKeepEntity savedKeep = scheduleKeepRepository.save(keep);

        notifyConverted(scope, savedKeep, savedSchedule, viewerUserId);

        return ConvertScheduleKeepResponse.builder()
                .keep(toResponse(savedKeep, scope))
                .schedule(ConvertScheduleKeepResponse.ConvertedScheduleDto.builder()
                        .id(savedSchedule.getId())
                        .title(savedSchedule.getTitle())
                        .startAt(savedSchedule.getStartAt())
                        .endAt(savedSchedule.getEndAt())
                        .allDay(savedSchedule.getAllDay())
                        .build())
                .build();
    }

    /**
     * 変換をキープ作成者へ通知する（§6.1・§2.1.1 の代償）。
     *
     * <p>通知は notification ドメインへの越境であり、失敗しても<b>変換は巻き戻さない</b>
     * （best-effort・§6.2）。ただし<b>握りつぶさずログには必ず残す</b>
     * （CLAUDE.md 障害対応の原則2）。</p>
     */
    private void notifyConverted(ScheduleKeepScope scope, ScheduleKeepEntity keep,
                                  ScheduleEntity schedule, Long actorUserId) {
        try {
            scheduleKeepNotificationService.notifyConverted(scope, keep, schedule, actorUserId);
        } catch (Exception ex) {
            log.warn("キープ変換通知の発行に失敗しました（変換自体は成立）: keepId={}, scheduleId={}, error={}",
                    keep.getId(), schedule.getId(), ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------
    // 並び替え（reorder・§4.4.1）
    // ------------------------------------------------------------------

    /**
     * キープを {@code orderedIds} の順に並び替える。
     *
     * <p><b>全件を先に検証してから一括更新する（部分適用しない）</b>のが本 API の核心である。
     * 1件でも不正なら何も変えない。検証を通しながら並行して更新すると、他スコープの ID を
     * 末尾に混ぜるだけで「自スコープ分だけ並び替わってからエラーになる」＝
     * 攻撃者が副作用だけ起こせる状態になる（AC-14e）。</p>
     *
     * <p><b>編集権限は不要</b>（MEMBER 以上なら可・§4.4.1）。並び順は「見え方の好み」であって
     * 内容の改変ではない。ただし並び順はスコープ共有であり、ユーザーごとには持たない。</p>
     */
    @Transactional
    public void reorder(ScheduleKeepScope scope, List<String> orderedIds, Long viewerUserId) {
        scheduleKeepAccessGuard.requireScopeAccess(scope, viewerUserId);

        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_REORDER);
        }
        // 件数上限はスコープの上限と同値（§4.4.1）。ID を引く前に弾き、
        // 巨大な配列で N 件分の SELECT を踏ませない。
        if (orderedIds.size() > maxKeptFor(scope)) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_REORDER);
        }

        List<UUID> ids = new ArrayList<>(orderedIds.size());
        for (String raw : orderedIds) {
            ids.add(parseKeepId(raw));
        }
        Set<UUID> distinct = new HashSet<>(ids);
        if (distinct.size() != ids.size()) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_REORDER);
        }

        // 1件ずつゲートを通す。まとめて引いてから件数だけ突き合わせる実装にすると、
        // 「スコープ込み finder を必ず経由する」という IDOR 防御の構造（§4.6.3）が緩む。
        // 不在・論理削除済み・他スコープ・不可視はすべて 404 に畳まれる。
        List<ScheduleKeepEntity> targets = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            targets.add(scheduleKeepAccessGuard.requireViewable(scope, id, viewerUserId));
        }

        // ARCHIVED は既定一覧に出ないので並び替え対象になりえない。混入は FE のバグを示す（§4.4.1）。
        boolean containsArchived = targets.stream()
                .anyMatch(keep -> keep.getStatus() == ScheduleKeepStatus.ARCHIVED);
        if (containsArchived) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_REORDER);
        }

        // ここまでで全件が正当。gap 採番で振り直す（§10.2）。
        // リクエストに含まれない同スコープのキープは据え置く（部分並び替えを許す）。
        int order = 0;
        for (ScheduleKeepEntity keep : targets) {
            keep.setSortOrder(order);
            order += SORT_ORDER_GAP;
        }
        scheduleKeepRepository.saveAll(targets);
    }

    private UUID parseKeepId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            // 解釈できない ID は「そんなキープは無い」と同じ扱いにする（形式の当否を漏らさない）。
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------
    // archive / restore / revert（§5.3 の状態 × 操作の全セル表）
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

    /**
     * 変換を取り消し、変換先の予定をカレンダーから消す（§5.3）。
     *
     * <p>{@code SCHEDULED} と {@code ARCHIVED}（{@code conv_id} 非 NULL）の両方から実行できる。
     * {@code restore}（状態を戻すだけ）との役割分担が本メソッドの存在理由であり、
     * 「予定ごと取り消したい」人はこちらを使う（§5.3.1）。</p>
     *
     * <p><b>出欠回答があるときは拒否する</b>（409 {@code _008}）。予定を消せば出欠行も意味を失うが、
     * 人が既に答えた事実を黙って捨てるのは対処療法である。消したいなら予定側で明示的に消す。</p>
     *
     * <p><b>変換先が既に消えているときは冪等に 200</b>（AC-11b）。取り消したい状態には既に到達しており、
     * エラーにするとユーザーが「キープが SCHEDULED のまま戻せない」袋小路に入る。</p>
     */
    @Transactional
    public ScheduleKeepResponse revert(ScheduleKeepScope scope, UUID keepId, Long viewerUserId) {
        ScheduleKeepEntity keep = scheduleKeepAccessGuard.requireEditable(scope, keepId, viewerUserId);
        Long convertedScheduleId = keep.getConvertedScheduleId();
        if (convertedScheduleId == null) {
            // 取り消す対象が無い（§5.3・SCHEDULE_KEEP_009）。KEPT への revert もここで落ちる。
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_INVALID_STATE_TRANSITION);
        }

        // 変換先はキープと同じスコープ列を持つ（§4.5.2）。スコープ込みで引くことで、
        // converted_schedule_id が何らかの理由で他スコープの予定を指していても消しに行かない。
        Optional<ScheduleEntity> converted = findScheduleWithinScope(scope, convertedScheduleId);
        if (converted.isPresent()) {
            if (scheduleAttendanceRepository.countByScheduleId(convertedScheduleId) > 0) {
                throw new BusinessException(ScheduleKeepErrorCode.KEEP_REVERT_BLOCKED_BY_ATTENDANCE);
            }
            ScheduleEntity schedule = converted.get();
            schedule.softDelete();
            scheduleRepository.save(schedule);
        }
        // 不在（既に論理削除済み）なら何も消さずに冪等成功へ進む。

        keep.setStatus(ScheduleKeepStatus.KEPT);
        keep.setConvertedScheduleId(null);
        ScheduleKeepEntity saved = scheduleKeepRepository.save(keep);
        return toResponse(saved, scope);
    }

    /**
     * 変換先の予定をパスのスコープ込みで引く。
     *
     * <p>{@code ScheduleEntity} の {@code @SQLRestriction("deleted_at IS NULL")} により
     * <b>論理削除済みは返らない</b>。これが「変換先が消えている」判定の実体である
     * （{@code revert} の冪等成功・{@code convertedScheduleState=DELETED} の双方で使う）。</p>
     */
    private Optional<ScheduleEntity> findScheduleWithinScope(ScheduleKeepScope scope, Long scheduleId) {
        return switch (scope.type()) {
            case TEAM -> scheduleRepository.findByIdAndTeamId(scheduleId, scope.id());
            case ORGANIZATION -> scheduleRepository.findByIdAndOrganizationId(scheduleId, scope.id());
            // ScheduleRepository に findByIdAndUserId が無いため、取得後に user_id を突き合わせる。
            // 比較を省くと個人スコープだけスコープ検証が抜ける（IDOR）ので必ず行う。
            case PERSONAL -> scheduleRepository.findById(scheduleId)
                    .filter(schedule -> java.util.Objects.equals(schedule.getUserId(), scope.id()));
        };
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

    /** スコープごとの {@code KEPT} 件数上限（§10.1）。 */
    private long maxKeptFor(ScheduleKeepScope scope) {
        return switch (scope.type()) {
            case PERSONAL -> MAX_KEPT_PERSONAL;
            case TEAM, ORGANIZATION -> MAX_KEPT_SHARED;
        };
    }

    /**
     * 件数上限に達していないことを確認する（§10.1）。
     *
     * <p>数えるのは {@code KEPT} だけであり、{@code ARCHIVED}/{@code SCHEDULED} は枠を消費しない
     * （「見送る」ことで枠が空く＝詰まったら整理すればよい、というのが上限の趣旨）。</p>
     *
     * <p>{@code COUNT} → {@code INSERT} は非原子的であり、同時リクエストで数件の超過が起こりうる。
     * <b>これは許容する</b>（§10.1）。上限は UX 上のガードであって整合性要件ではなく、
     * 301 件になっても何も壊れない。悲観ロックのコストに見合わない。</p>
     */
    private void requireCapacityAvailable(ScheduleKeepScope scope) {
        long current = switch (scope.type()) {
            case TEAM -> scheduleKeepRepository.countByTeamIdAndStatus(scope.id(), ScheduleKeepStatus.KEPT);
            case ORGANIZATION ->
                    scheduleKeepRepository.countByOrganizationIdAndStatus(scope.id(), ScheduleKeepStatus.KEPT);
            case PERSONAL -> scheduleKeepRepository.countByUserIdAndStatus(scope.id(), ScheduleKeepStatus.KEPT);
        };
        if (current >= maxKeptFor(scope)) {
            throw new BusinessException(ScheduleKeepErrorCode.KEEP_LIMIT_EXCEEDED);
        }
    }

    /**
     * 一覧分の変換先の生存状態を 1 クエリで解決する（§4.5.1・N+1 回避）。
     *
     * @param keeps 対象キープ
     * @return {@code schedules.id} → {@code ACTIVE}/{@code CANCELLED} のマップ。
     *         <b>マップに載らない ID は「消えている」＝{@code DELETED}</b>（{@code @SQLRestriction} により
     *         論理削除済みは取得されないため、不在がそのまま削除の証拠になる）
     */
    private Map<Long, String> resolveConvertedScheduleStates(List<ScheduleKeepEntity> keeps) {
        List<Long> scheduleIds = keeps.stream()
                .map(ScheduleKeepEntity::getConvertedScheduleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (scheduleIds.isEmpty()) {
            return Map.of();
        }
        // ID の出所は既にスコープ検証済みのキープ行であり、ここで読むのは status のみ
        // （認可判定には使わない）。よって主キー一括取得でよい。
        Map<Long, String> states = new HashMap<>();
        for (ScheduleEntity schedule : scheduleRepository.findAllById(scheduleIds)) {
            states.put(schedule.getId(), scheduleStateOf(schedule));
        }
        return states;
    }

    /** 予定 1 件の生存状態（§5.4）。中止と論理削除は FE の案内文が違うので畳まない。 */
    private String scheduleStateOf(ScheduleEntity schedule) {
        return schedule.getStatus() == ScheduleStatus.CANCELLED ? "CANCELLED" : "ACTIVE";
    }

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

    /** 単体用。変換先の状態はその場で 1 件だけ引く（一覧は {@link #resolveConvertedScheduleStates} を使う）。 */
    private ScheduleKeepResponse toResponse(ScheduleKeepEntity entity, ScheduleKeepScope scope) {
        Map<Long, String> states = entity.getConvertedScheduleId() == null
                ? Map.of()
                : resolveConvertedScheduleStates(List.of(entity));
        return toResponse(entity, scope, states);
    }

    private ScheduleKeepResponse toResponse(ScheduleKeepEntity entity, ScheduleKeepScope scope,
                                             Map<Long, String> stateByScheduleId) {
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
                // 未変換は NONE。変換済みなのに引けなかったものは論理削除済み＝DELETED（§5.4）。
                .convertedScheduleState(entity.getConvertedScheduleId() == null
                        ? "NONE"
                        : stateByScheduleId.getOrDefault(entity.getConvertedScheduleId(), "DELETED"))
                .sortOrder(entity.getSortOrder())
                .createdBy(createdBy)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

}
