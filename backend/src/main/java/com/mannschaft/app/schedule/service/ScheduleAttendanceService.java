package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceSolicitationSettings;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.AttendanceSummaryResponse;
import com.mannschaft.app.schedule.dto.AttendanceTeamBreakdownResponse;
import com.mannschaft.app.schedule.dto.BulkAttendanceRequest;
import com.mannschaft.app.schedule.dto.SurveyResponseRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.AttendanceRespondedEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 出欠管理サービス。出欠回答・集計・CSV出力・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleAttendanceService {

    private static final String CSV_HEADER = "ユーザーID,ステータス,コメント,回答日時";

    /** F03.1: 組織出欠チーム別内訳 CSV のヘッダ。 */
    private static final String CSV_TEAM_BREAKDOWN_HEADER = "チーム名,出席,一部参加,欠席,未回答,合計";

    /** F03.1: チーム未所属（組織直接メンバー）枠の CSV 表示ラベル。 */
    private static final String CSV_TEAM_UNASSIGNED_LABEL = "チーム未所属（組織直接メンバー）";

    /** 機能55: 出欠募集通知の種別（自由文字列。NotificationService の type 引数に渡す）。 */
    private static final String NOTIFICATION_TYPE_ATTENDANCE_REQUEST = "SCHEDULE_ATTENDANCE_REQUEST";
    private static final String NOTIFICATION_SOURCE_TYPE = "SCHEDULE";

    private final ScheduleAttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleService scheduleService;
    private final EventSurveyService eventSurveyService;
    private final UserRoleRepository userRoleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final ScheduleDelegationService scheduleDelegationService;
    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;

    /**
     * (B) 組織→参加チーム配信 案C フェーズA: 組織スコープ配信の宛先解決窓口。
     * {@code team_org_memberships} / {@code memberships} を直接参照せず、本窓口経由で
     * 「直属 ∪ 配下参加チーム(ACTIVE)」のユーザーIDを解決する（越境是正）。
     */
    private final OrganizationMembershipService organizationMembershipService;

    /**
     * F22.1 第二波: 統合「要対応」集計の per-scope 認可に使用する。
     * Bean 不在のテスト構成（Mockito {@code @InjectMocks}）では null 注入され、ガードはスキップされる。
     */
    private final AccessControlService accessControlService;

    /**
     * 出欠回答を行う。期限チェック・コメント必須チェックを実施し、
     * アンケート回答がある場合は同時に保存する。
     *
     * @param scheduleId スケジュールID
     * @param userId     ユーザーID
     * @param req        出欠回答リクエスト
     * @return 出欠回答レスポンス
     */
    // TODO: scheduleドメインとproxyドメインをまたいでいる（ProxyInputRecordRepositoryを直接参照）。将来はProxyInputServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    @Transactional
    public AttendanceResponse respondAttendance(Long scheduleId, Long userId, AttendanceRequest req) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);
        validateAttendanceRequired(schedule);
        validateMinResponseRole(schedule, userId);
        validateAttendanceDeadline(schedule);
        validateComment(schedule, req.getComment());

        AttendanceStatus newStatus = AttendanceStatus.valueOf(req.getStatus());

        ScheduleAttendanceEntity attendance = attendanceRepository
                .findByScheduleIdAndUserId(scheduleId, userId)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));

        attendance.respond(newStatus, req.getComment());
        attendance = attendanceRepository.save(attendance);

        // 代理入力の場合: proxy_input_records を作成し、出欠エンティティにフラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveProxyInputRecord(
                    "SCHEDULE_ATTENDANCE", attendance.getId());
            attendance = attendanceRepository.save(attendance.toBuilder()
                    .isProxyInput(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        // アンケート回答の同時保存
        if (req.getSurveyResponses() != null && !req.getSurveyResponses().isEmpty()) {
            for (SurveyResponseRequest surveyReq : req.getSurveyResponses()) {
                eventSurveyService.respondToSurvey(surveyReq.getSurveyId(), userId, surveyReq);
            }
        }

        // F03.10 §5.3: 委任者が自分の出欠を ATTENDING に更新したら PENDING 代理を自動 CANCELLED にする
        scheduleDelegationService.onDelegatorAttendanceChanged(scheduleId, userId, newStatus);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new AttendanceRespondedEvent(
                scheduleId, userId, newStatus.name()));

        log.info("出欠回答: scheduleId={}, userId={}, status={}", scheduleId, userId, newStatus);
        return toAttendanceResponse(attendance);
    }

    /**
     * スケジュールの出欠一覧を取得する。
     *
     * <p><b>認可（認可根治 Wave3-B6）</b>: 個人名付き出欠一覧の漏洩を防ぐため、当該スケジュールが
     * 属する scope（TEAM/ORGANIZATION）のメンバーのみ閲覧可（{@code checkMembership} 水準）。
     * entity 由来 scope で判定するため、URL の teamId/orgId と実際のスケジュールの scope が
     * 一致しない BOLA 越境も防ぐ。</p>
     *
     * @param scheduleId スケジュールID
     * @param userId     閲覧ユーザーID
     * @return 出欠回答一覧
     */
    public List<AttendanceResponse> getAttendances(Long scheduleId, Long userId) {
        scheduleService.checkScopeViewAccess(scheduleId, userId);
        return attendanceRepository.findByScheduleIdOrderByUserIdAsc(scheduleId).stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    /**
     * 出欠集計サマリーを取得する。ATTENDING/PARTIAL/ABSENT/UNDECIDED の各件数を返す。
     *
     * <p><b>認可（認可根治 Wave6）</b>: {@code getAttendances} と同じく entity 由来 scope
     * （TEAM/ORGANIZATION）のメンバーのみ閲覧可（{@code checkScopeViewAccess} 水準）。</p>
     *
     * @param scheduleId スケジュールID
     * @param userId     閲覧ユーザーID
     * @return 出欠サマリー
     */
    public AttendanceSummaryResponse getAttendanceSummary(Long scheduleId, Long userId) {
        scheduleService.checkScopeViewAccess(scheduleId, userId);
        scheduleService.getSchedule(scheduleId);

        Map<AttendanceStatus, Integer> countMap = new HashMap<>();
        for (AttendanceStatus status : AttendanceStatus.values()) {
            countMap.put(status, 0);
        }

        List<Object[]> results = attendanceRepository.countByScheduleIdGroupByStatus(scheduleId);
        for (Object[] row : results) {
            AttendanceStatus status = (AttendanceStatus) row[0];
            int count = ((Long) row[1]).intValue();
            countMap.put(status, count);
        }

        int total = countMap.values().stream().mapToInt(Integer::intValue).sum();

        return new AttendanceSummaryResponse(
                countMap.get(AttendanceStatus.ATTENDING),
                countMap.get(AttendanceStatus.PARTIAL),
                countMap.get(AttendanceStatus.ABSENT),
                countMap.get(AttendanceStatus.UNDECIDED),
                total);
    }

    /**
     * 組織スケジュールの出欠を「チームごとの内訳（by_team）」で集計する
     * （(B) 組織→参加チーム配信 案C フェーズB・出欠のチーム別内訳）。
     *
     * <p><b>トグル後方互換</b>: スケジュールの {@code team_breakdown_enabled} が OFF（既定）または
     * 組織スコープでない場合は {@code byTeam = null}（省略）で返す＝従来挙動。トグル ON の組織
     * スケジュールでのみ byTeam を算出する。{@code total} はトグルに関わらず常に返す（実人数集計）。</p>
     *
     * <p><b>御裁可A（全チーム計上・total は DISTINCT 別建て）</b>:</p>
     * <ul>
     *   <li>{@code total}: 各ユーザーの出欠行は 1 件のため、ステータス別の単純集計がそのまま実人数になる
     *       （DISTINCT 母数）。</li>
     *   <li>{@code byTeam}: {@link OrganizationMembershipService#resolveMemberTeams(Long, boolean)} が返す
     *       「userId → 所属チーム（複数・組織直属は teamId=null 枠）」を用い、各ユーザーのステータスを
     *       <b>所属全チームへ 1 票ずつ計上</b>する（重複計上あり）。したがって byTeam 各チームの合計は
     *       total（実人数）以上になりうる。</li>
     * </ul>
     *
     * <p>母集団の SUPPORTER 包含は配信時と同じく {@code schedules.include_supporters} トグルに従う
     * （配信＝集計の母集団一致）。出欠行が存在するが母集団に含まれないユーザー（退会等）は byTeam では
     * いずれのチームにも計上されない（total には出欠行ベースで計上される）。</p>
     *
     * @param scheduleId スケジュールID
     * @return 全体集計＋チーム別内訳。トグル OFF / 非組織スコープでは byTeam = null
     */
    public AttendanceTeamBreakdownResponse getAttendanceTeamBreakdown(Long scheduleId) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);

        // 全体集計（実人数・DISTINCT 母数）: ステータス別件数をそのまま使う（1 ユーザー 1 行）。
        Map<AttendanceStatus, Integer> totalCount = new HashMap<>();
        for (AttendanceStatus status : AttendanceStatus.values()) {
            totalCount.put(status, 0);
        }
        for (Object[] row : attendanceRepository.countByScheduleIdGroupByStatus(scheduleId)) {
            totalCount.put((AttendanceStatus) row[0], ((Long) row[1]).intValue());
        }
        AttendanceTeamBreakdownResponse.TeamBreakdownCounts total =
                new AttendanceTeamBreakdownResponse.TeamBreakdownCounts(
                        totalCount.get(AttendanceStatus.ATTENDING),
                        totalCount.get(AttendanceStatus.PARTIAL),
                        totalCount.get(AttendanceStatus.ABSENT),
                        totalCount.get(AttendanceStatus.UNDECIDED));

        // トグル OFF / 非組織スコープ: byTeam は省略（従来挙動）。
        boolean teamBreakdownEnabled = Boolean.TRUE.equals(schedule.getTeamBreakdownEnabled());
        if (!schedule.isOrganizationScope() || !teamBreakdownEnabled) {
            return new AttendanceTeamBreakdownResponse(scheduleId, total, null);
        }

        // userId → status（出欠行ベース）。
        Map<Long, AttendanceStatus> statusByUser = new HashMap<>();
        for (ScheduleAttendanceEntity a : attendanceRepository.findByScheduleIdOrderByUserIdAsc(scheduleId)) {
            statusByUser.put(a.getUserId(), a.getStatus());
        }

        // userId → 所属チーム（複数・組織直属は teamId=null 枠）。母集団の SUPPORTER 包含は配信トグルに従う。
        boolean includeSupporters = Boolean.TRUE.equals(schedule.getIncludeSupporters());
        Map<Long, List<OrganizationMembershipService.TeamRef>> memberTeams =
                organizationMembershipService.resolveMemberTeams(schedule.getOrganizationId(), includeSupporters);

        // teamId（null=組織直接枠）→ ステータス別カウント（重複計上）。出現順を安定させるため LinkedHashMap。
        Map<Long, TeamBucket> buckets = new java.util.LinkedHashMap<>();
        Map<Long, String> teamNameById = new HashMap<>();
        for (Map.Entry<Long, List<OrganizationMembershipService.TeamRef>> entry : memberTeams.entrySet()) {
            Long userId = entry.getKey();
            AttendanceStatus status = statusByUser.get(userId);
            if (status == null) {
                // 母集団には属するが出欠行が無い（生成漏れ等）。byTeam では計上しない。
                continue;
            }
            for (OrganizationMembershipService.TeamRef ref : entry.getValue()) {
                Long teamKey = ref.teamId(); // null = 組織直接メンバー枠
                buckets.computeIfAbsent(teamKey, k -> new TeamBucket()).add(status);
                if (teamKey != null && ref.teamName() != null) {
                    teamNameById.putIfAbsent(teamKey, ref.teamName());
                }
            }
        }

        List<AttendanceTeamBreakdownResponse.TeamBreakdownItem> byTeam = new ArrayList<>();
        for (Map.Entry<Long, TeamBucket> e : buckets.entrySet()) {
            Long teamKey = e.getKey();
            TeamBucket b = e.getValue();
            byTeam.add(new AttendanceTeamBreakdownResponse.TeamBreakdownItem(
                    teamKey,
                    teamKey == null ? null : teamNameById.get(teamKey),
                    b.attending, b.partial, b.absent, b.undecided));
        }

        return new AttendanceTeamBreakdownResponse(scheduleId, total, byTeam);
    }

    /**
     * 組織スケジュールの出欠チーム別内訳を CSV 文字列として出力する
     * （F03.1: {@code チーム名,出席,一部参加,欠席,未回答,合計} ＋末尾「合計」行）。
     *
     * <p>BOM 付き UTF-8（Excel 互換）。「チーム未所属（組織直接メンバー）」枠は
     * {@code teamName} 列に固定ラベルを出力する。トグル OFF / 非組織スコープでは byTeam が無いため
     * ヘッダ＋合計行のみ（合計は実人数 total）を出力する。</p>
     *
     * @param scheduleId スケジュールID
     * @return CSV 文字列（BOM 付き）
     */
    public String exportAttendanceTeamBreakdownCsv(Long scheduleId) {
        AttendanceTeamBreakdownResponse breakdown = getAttendanceTeamBreakdown(scheduleId);

        StringJoiner csv = new StringJoiner("\n");
        csv.add(CSV_TEAM_BREAKDOWN_HEADER);

        if (breakdown.getByTeam() != null) {
            for (AttendanceTeamBreakdownResponse.TeamBreakdownItem item : breakdown.getByTeam()) {
                String teamName = item.teamId() == null
                        ? CSV_TEAM_UNASSIGNED_LABEL
                        : (item.teamName() != null ? item.teamName() : "");
                int sum = item.attending() + item.partial() + item.absent() + item.undecided();
                csv.add(csvEscape(teamName) + "," + item.attending() + "," + item.partial()
                        + "," + item.absent() + "," + item.undecided() + "," + sum);
            }
        }

        AttendanceTeamBreakdownResponse.TeamBreakdownCounts t = breakdown.getTotal();
        int totalSum = t.attending() + t.partial() + t.absent() + t.undecided();
        csv.add("合計," + t.attending() + "," + t.partial() + "," + t.absent()
                + "," + t.undecided() + "," + totalSum);

        // BOM 付き UTF-8（Excel 互換）
        return "﻿" + csv.toString();
    }

    /** チーム別内訳の集計バケット（可変・重複計上用）。 */
    private static final class TeamBucket {
        private int attending;
        private int partial;
        private int absent;
        private int undecided;

        void add(AttendanceStatus status) {
            switch (status) {
                case ATTENDING -> attending++;
                case PARTIAL -> partial++;
                case ABSENT -> absent++;
                case UNDECIDED -> undecided++;
            }
        }
    }

    /** CSV のチーム名にカンマ・ダブルクオート・改行が含まれる場合のエスケープ。 */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 管理者による出欠一括更新を行う。
     *
     * <p><b>認可（認可根治 Wave3-B6）</b>: 「管理者用」の doc どおり、当該スケジュールが属する
     * scope（TEAM/ORGANIZATION）の ADMIN/DEPUTY_ADMIN のみ実行可（{@code checkAdminOrAbove} 水準・
     * entity 由来 scope）。従来は認可ゼロで一般メンバーも一括上書きできていた欠陥を是正。</p>
     *
     * @param scheduleId スケジュールID
     * @param req        一括出欠リクエスト
     * @param userId     操作ユーザーID
     */
    @Transactional
    public void bulkUpdateAttendances(Long scheduleId, BulkAttendanceRequest req, Long userId) {
        scheduleService.checkScopeAdminAccess(scheduleId, userId);
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);
        validateAttendanceRequired(schedule);

        for (BulkAttendanceRequest.BulkAttendanceItem item : req.getAttendances()) {
            ScheduleAttendanceEntity attendance = attendanceRepository
                    .findByScheduleIdAndUserId(scheduleId, item.userId())
                    .orElse(null);

            if (attendance != null) {
                AttendanceStatus newStatus = AttendanceStatus.valueOf(item.status());
                attendance.respond(newStatus, item.comment());
                attendanceRepository.save(attendance);
            }
        }

        log.info("出欠一括更新: scheduleId={}, 件数={}", scheduleId, req.getAttendances().size());
    }

    /**
     * 出欠一覧をCSV文字列として出力する。
     *
     * <p><b>認可（認可根治 Wave3-B6）</b>: getAttendances と同じく entity 由来 scope のメンバーのみ。</p>
     *
     * @param scheduleId スケジュールID
     * @param userId     閲覧ユーザーID
     * @return CSV文字列
     */
    public String exportAttendancesCsv(Long scheduleId, Long userId) {
        scheduleService.checkScopeViewAccess(scheduleId, userId);
        List<ScheduleAttendanceEntity> attendances = attendanceRepository
                .findByScheduleIdOrderByUserIdAsc(scheduleId);

        StringJoiner csv = new StringJoiner("\n");
        csv.add(CSV_HEADER);

        for (ScheduleAttendanceEntity a : attendances) {
            String comment = a.getComment() != null ? "\"" + a.getComment().replace("\"", "\"\"") + "\"" : "";
            String respondedAt = a.getRespondedAt() != null ? a.getRespondedAt().toString() : "";
            csv.add(a.getUserId() + "," + a.getStatus().name() + "," + comment + "," + respondedAt);
        }

        return csv.toString();
    }

    /**
     * ログインユーザーの出欠回答ステータスを取得する。
     *
     * @param scheduleId スケジュールID
     * @param userId     ユーザーID
     * @return 出欠ステータス名（ATTENDING/PARTIAL/ABSENT/UNDECIDED）。レコードが存在しない場合は empty
     */
    public Optional<String> getMyAttendanceStatus(Long scheduleId, Long userId) {
        return attendanceRepository.findByScheduleIdAndUserId(scheduleId, userId)
                .map(e -> e.getStatus().name());
    }

    /**
     * F22.1 第二波: 指定スコープで当該ユーザーが「未回答（PENDING / UNDECIDED）」の直近イベントを取得する。
     *
     * <p>横スワイプ・ダッシュボードの統合「要対応」集計（{@code ScopeActionRequiredFacade}）から
     * 呼ばれる読み取り専用メソッド。<b>per-scope 認可をこのメソッド内で必ず通す</b>
     * （{@link AccessControlService#checkMembership}・Bean 不在のテストではスキップ）。
     * 非所属ユーザーは {@code COMMON_002} で弾かれる（集計バイパス禁止・02 §3.4）。</p>
     *
     * <p>未回答 = 出欠行が {@code status = UNDECIDED} かつ {@code respondedAt IS NULL}、
     * 対象は {@code attendanceRequired = true} かつ開始が現在以降のイベント。N+1 を避けるため
     * JOIN クエリで判定し、開始時刻の昇順で {@code limit} 件に絞る。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID
     * @param limit     直近アイテムの最大件数
     * @return 未回答の総件数と limit 件のアイテム
     */
    public UnansweredAttendances getUnansweredForUserInScope(
            String scopeType, Long scopeId, Long userId, int limit) {
        if (accessControlService != null) {
            // 配信＝受信権 統一: 未回答集計の入口は広め（includeSupporters=true）で通す。
            // findUnansweredUpcomingForUserIn{Organization,Team} は userId の出欠行（materialize 済み）
            // のみ返すため、配信母集団外ユーザーは 0 件になり過小排除も漏洩も起きない。トグル ON 配信の
            // 配下 SUPPORTER も入口で弾かれないようにする（ScopeActionRequiredFacade と同方針）。
            accessControlService.checkMembershipOrDescendant(userId, scopeId, scopeType, true);
        }
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleEntity> all;
        if ("ORGANIZATION".equalsIgnoreCase(scopeType)) {
            all = attendanceRepository.findUnansweredUpcomingForUserInOrganization(scopeId, userId, now);
        } else {
            all = attendanceRepository.findUnansweredUpcomingForUserInTeam(scopeId, userId, now);
        }
        List<ScheduleEntity> items = all.size() > limit ? all.subList(0, limit) : all;
        return new UnansweredAttendances(all.size(), List.copyOf(items));
    }

    /**
     * F22.1 第二波: 未回答出欠の集計結果（件数 + 直近イベント）。
     *
     * @param unansweredCount 未回答の総件数
     * @param items           直近イベント（limit 件）
     */
    public record UnansweredAttendances(
            long unansweredCount,
            List<ScheduleEntity> items) {
    }

    /**
     * 対象メンバーの出欠レコードを一括生成する。スケジュール作成時にイベントリスナーから呼ばれる。
     *
     * <p><b>規模対応 Tier2</b>: per-user の {@code save} ループを {@code saveAll} のバッチ INSERT に変更し、
     * 組織配下展開で対象が数千〜数万に膨らんでも N 回の round-trip を避ける
     * （{@code spring.jpa.properties.hibernate.jdbc.batch_size} とあわせて 1 ステートメントあたり
     * 複数行をまとめて INSERT する）。</p>
     *
     * <p>TODO（規模対応 Tier3）: 数万規模では単一トランザクションでの一括 INSERT も長大化するため、
     * チャンク分割（例: 1,000 件ごとの非同期ジョブ）化が望ましい。本フェーズ A では saveAll バッチ＋
     * 呼び出し元の非同期化（AFTER_COMMIT @Async / バッチスレッド）までを範囲とする。</p>
     *
     * @param scheduleId    スケジュールID
     * @param memberUserIds 対象メンバーのユーザーIDリスト
     */
    @Transactional
    public void generateAttendanceRecords(Long scheduleId, List<Long> memberUserIds) {
        List<ScheduleAttendanceEntity> records = memberUserIds.stream()
                .map(userId -> (ScheduleAttendanceEntity) ScheduleAttendanceEntity.builder()
                        .scheduleId(scheduleId)
                        .userId(userId)
                        .status(AttendanceStatus.UNDECIDED)
                        .isProxyInput(false)
                        .build())
                .toList();
        attendanceRepository.saveAll(records);

        log.info("出欠レコード生成: scheduleId={}, 件数={}", scheduleId, memberUserIds.size());
    }

    /**
     * 出欠募集を開始する（機能55 第二陣・RSVP 根治）。
     *
     * <p>予定のスコープ（TEAM / ORGANIZATION）から対象メンバーを解決し、出欠レコードを生成して
     * 対象メンバーへ「出欠募集」通知（IN_APP + PUSH）を配信する。即時ケース（予約タスクなし）は
     * {@code ScheduleAttendanceSolicitationEventListener} が、予約ケース（scheduledAt 到来）は
     * {@code ScheduleScheduledTaskBatchService} がそれぞれ本メソッドを呼ぶ。</p>
     *
     * <p><b>冪等性</b>: 既に出欠レコードが生成済みの場合は二重生成・二重通知を行わずスキップする
     * （バッチ再試行や即時/予約の二重発火に対する防御）。</p>
     *
     * <p>PERSONAL スコープの予定には出欠募集の概念が無いためスキップする。</p>
     *
     * @param scheduleId 対象予定 schedules.id
     */
    @Transactional
    public void openAttendanceSolicitation(Long scheduleId) {
        openAttendanceSolicitation(scheduleId, AttendanceSolicitationSettings.NONE);
    }

    /**
     * 出欠設定を適用しつつ出欠募集を開始する（機能55 / Issue #2508 欠陥B）。
     *
     * <p>予約出欠募集（{@code payload_json}）でユーザーが指定した回答締切・コメント要否・
     * 最低応答ロールを、募集開始のタイミングで予定本体へ適用してから募集を行う。
     * {@code settings} の各項目は <b>null = 未指定</b> で、その場合は予定の既存値を保つ。</p>
     *
     * <p><b>回帰防止</b>: 以前は materialize バッチが {@code payload_json} を一度も読まず、
     * ユーザーが指定した設定が保存されるだけで一切適用されなかった。設定を引数で受け取ることで
     * 「経路の途中で黙って捨てられる」ことを構造的に防ぐ。</p>
     *
     * <p>設定の適用は冪等性ガード（既に出欠レコードがある場合のスキップ）よりも <b>前</b> に行う。
     * 募集が既に開始済みでも、予約された時刻に指定どおりの締切へ更新されるべきだからである。</p>
     *
     * @param scheduleId 対象予定 schedules.id
     * @param settings   募集開始時に適用する出欠設定（null 不可。設定なしは
     *                   {@link AttendanceSolicitationSettings#NONE}）
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。機能55: 2026-06-01
    // NOTE: (B) 組織→参加チーム配信 案C フェーズAで、ORGANIZATION スコープの宛先解決のみ
    //       organization ドメインの窓口 OrganizationMembershipService.resolveOrgDistributionUserIds
    //       経由に部分是正済み（team_org_memberships / memberships 直参照を排除）。
    //       TEAM スコープは従来どおり UserRoleRepository.findUserIdsByScope を使う。
    @Transactional
    public void openAttendanceSolicitation(Long scheduleId, AttendanceSolicitationSettings settings) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);

        String scopeType;
        Long scopeId;
        if (schedule.isTeamScope()) {
            scopeType = "TEAM";
            scopeId = schedule.getTeamId();
        } else if (schedule.isOrganizationScope()) {
            scopeType = "ORGANIZATION";
            scopeId = schedule.getOrganizationId();
        } else {
            // PERSONAL スコープには出欠募集の概念が無い
            log.debug("出欠募集スキップ（PERSONALスコープ）: scheduleId={}", scheduleId);
            return;
        }

        // 予約時に指定された出欠設定を予定へ適用する（未指定項目は既存値を保つ）。
        // 冪等性ガードより前に行い、募集済みでも予約された設定が確実に反映されるようにする。
        if (settings != null && !settings.isEmpty()) {
            schedule.applyAttendanceSolicitationSettings(
                    settings.attendanceDeadline(), settings.commentOption(), settings.minResponseRole());
            scheduleRepository.save(schedule);
            log.info("出欠募集設定を適用: scheduleId={}, deadline={}, commentOption={}, minResponseRole={}",
                    scheduleId, settings.attendanceDeadline(),
                    settings.commentOption(), settings.minResponseRole());
        }

        // 冪等性ガード: 既に出欠レコードが生成済みなら何もしない（二重募集防止）
        if (attendanceRepository.countByScheduleId(scheduleId) > 0) {
            log.info("出欠募集スキップ（既に生成済み）: scheduleId={}", scheduleId);
            return;
        }

        // 対象メンバーの解決
        // - ORGANIZATION: organization ドメインの窓口で「直属 ∪ 配下参加チーム(ACTIVE)」を解決し、
        //   schedule.includeSupporters トグルに従い SUPPORTER（応援者）を含める/除外する。
        // - TEAM: 従来どおり scope ベース（配下展開なし・includeSupporters は TEAM には適用しない。
        //   フェーズ A は組織配信が主眼）。
        List<Long> memberUserIds;
        if ("ORGANIZATION".equals(scopeType)) {
            boolean includeSupporters = Boolean.TRUE.equals(schedule.getIncludeSupporters());
            memberUserIds = organizationMembershipService
                    .resolveOrgDistributionUserIds(scopeId, includeSupporters).stream()
                    .distinct()
                    .toList();
        } else {
            memberUserIds = userRoleRepository.findUserIdsByScope(scopeType, scopeId).stream()
                    .distinct()
                    .toList();
        }
        if (memberUserIds.isEmpty()) {
            log.info("出欠募集スキップ（対象メンバー0名）: scheduleId={}, scope={}:{}",
                    scheduleId, scopeType, scopeId);
            return;
        }

        // 出欠レコード生成
        generateAttendanceRecords(scheduleId, memberUserIds);

        // 出欠募集通知（IN_APP + PUSH）
        NotificationScopeType notifScope = "ORGANIZATION".equals(scopeType)
                ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
        String title = "出欠の回答をお願いします";
        String body = "「" + schedule.getTitle() + "」の出欠回答が募集されています。期日までに回答してください。";
        String actionUrl = "/schedules/" + scheduleId;

        // TODO（規模対応 Tier3）: 数万規模の組織配信では、出欠レコード一括生成＋per-user 通知配信を
        //   この単一トランザクション内で同期実行すると長大化する。チャンク分割した非同期ジョブ
        //   （例: 1,000 件ごとに job-pool へ投入し、各チャンクを REQUIRES_NEW で確定）に移行するのが望ましい。
        //   フェーズ A では「呼び出し元の非同期化（即時=AFTER_COMMIT @Async リスナー / 予約=バッチスレッド）」＋
        //   「saveAll バッチ INSERT」までを範囲とし、リクエストをブロックしない構造は確保済み。
        // 配信＝受信権 統一（関所(1)通知）: 受信者は配信母集団（ORG=resolveOrgDistributionUserIds の
        // includeSupporters トグル準拠 / TEAM=findUserIdsByScope）で事前認可済みのため、
        // createNotificationPreAuthorized を使い canView 二重判定をスキップする。これにより
        // SURVEY/SCHEDULE の visibility（結果閲覧軸を含む）誤 deny で通知が届かない (B) レグを回避する。
        int dispatched = 0;
        for (Long userId : memberUserIds) {
            NotificationEntity notification = notificationService.createNotificationPreAuthorized(
                    userId,
                    NOTIFICATION_TYPE_ATTENDANCE_REQUEST,
                    NotificationPriority.NORMAL,
                    title, body,
                    NOTIFICATION_SOURCE_TYPE, scheduleId,
                    notifScope, scopeId,
                    actionUrl, schedule.getCreatedBy());
            notificationDispatchService.dispatch(notification);
            dispatched++;
        }

        log.info("出欠募集開始: scheduleId={}, scope={}:{}, 対象={}名, 通知配信={}件",
                scheduleId, scopeType, scopeId, memberUserIds.size(), dispatched);
    }

    /**
     * チームの出席率統計を取得する。
     *
     * <p><b>認可（認可根治 Wave6）</b>: 名簿全体・期間横断のユーザー別出席率という管理者向け集計のため、
     * 当該チームの ADMIN/DEPUTY_ADMIN のみ取得可（{@code checkAdminOrAbove} 水準。
     * {@code bulkUpdateAttendances} と同段）。SYSTEM_ADMIN は横断で許可。</p>
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @param userId 閲覧ユーザーID
     * @return 出席率統計（ユーザー別）
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public List<AttendanceStatsResponse> getTeamAttendanceStats(Long teamId,
                                                                  LocalDateTime from, LocalDateTime to,
                                                                  Long userId) {
        checkScopeAdmin(userId, teamId, "TEAM");
        // チームメンバーのユーザーIDリストを取得
        Page<UserRoleEntity> memberPage = userRoleRepository.findByTeamId(teamId, PageRequest.of(0, 10000));
        List<Long> memberUserIds = memberPage.getContent().stream()
                .map(UserRoleEntity::getUserId)
                .distinct()
                .toList();

        // チームスコープのスケジュールを期間指定で取得
        List<ScheduleEntity> schedules = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, from, to);
        List<Long> scheduleIds = schedules.stream()
                .map(ScheduleEntity::getId)
                .toList();

        // 各メンバーの出欠を集計
        return buildAttendanceStats(memberUserIds, scheduleIds);
    }

    /**
     * 組織の出席率統計を取得する。
     *
     * <p><b>認可（認可根治 Wave6）</b>: {@link #getTeamAttendanceStats} と同水準（ORGANIZATION 系）。</p>
     *
     * @param orgId  組織ID
     * @param from   期間開始
     * @param to     期間終了
     * @param userId 閲覧ユーザーID
     * @return 出席率統計（ユーザー別）
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public List<AttendanceStatsResponse> getOrgAttendanceStats(Long orgId,
                                                                 LocalDateTime from, LocalDateTime to,
                                                                 Long userId) {
        checkScopeAdmin(userId, orgId, "ORGANIZATION");
        // 組織メンバーのユーザーIDリストを取得
        Page<UserRoleEntity> memberPage = userRoleRepository.findByOrganizationId(orgId, PageRequest.of(0, 10000));
        List<Long> memberUserIds = memberPage.getContent().stream()
                .map(UserRoleEntity::getUserId)
                .distinct()
                .toList();

        // 組織スコープのスケジュールを期間指定で取得
        List<ScheduleEntity> schedules = scheduleRepository
                .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, from, to);
        List<Long> scheduleIds = schedules.stream()
                .map(ScheduleEntity::getId)
                .toList();

        // 各メンバーの出欠を集計
        return buildAttendanceStats(memberUserIds, scheduleIds);
    }

    /**
     * 個人の出席率統計を取得する。
     *
     * @param userId ユーザーID
     * @param from   期間開始
     * @param to     期間終了
     * @return 出席率統計
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public AttendanceStatsResponse getMyAttendanceStats(Long userId, LocalDateTime from, LocalDateTime to) {
        // ユーザーが所属するチームのスケジュールIDを収集
        List<Long> allScheduleIds = new ArrayList<>();

        // CMP-027: user_roles ∪ memberships の在籍チーム（素メンバー/応援者を取りこぼさない）
        for (Long teamId : userRoleRepository.findTeamIdsByUserId(userId)) {
            List<ScheduleEntity> teamSchedules = scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, from, to);
            allScheduleIds.addAll(teamSchedules.stream().map(ScheduleEntity::getId).toList());
        }

        // ユーザーが所属する組織のスケジュールIDを収集（CMP-027: user_roles ∪ memberships の在籍組織）
        for (Long orgId : userRoleRepository.findOrganizationIdsByUserId(userId)) {
            List<ScheduleEntity> orgSchedules = scheduleRepository
                    .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, from, to);
            allScheduleIds.addAll(orgSchedules.stream().map(ScheduleEntity::getId).toList());
        }

        // 重複排除
        List<Long> uniqueScheduleIds = allScheduleIds.stream().distinct().toList();

        // 該当スケジュールに対するユーザーの出欠を集計
        List<ScheduleAttendanceEntity> userAttendances = uniqueScheduleIds.stream()
                .map(scheduleId -> attendanceRepository.findByScheduleIdAndUserId(scheduleId, userId))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();

        int totalSchedules = userAttendances.size();
        int attended = (int) userAttendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.ATTENDING).count();
        int absent = (int) userAttendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.ABSENT).count();
        int partial = (int) userAttendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.PARTIAL).count();
        double rate = totalSchedules > 0 ? (double) attended / totalSchedules * 100.0 : 0.0;

        return new AttendanceStatsResponse(userId, totalSchedules, attended, absent, partial, rate);
    }

    // --- プライベートメソッド ---

    /**
     * スコープ（TEAM/ORGANIZATION）の ADMIN/DEPUTY_ADMIN 認可を強制する（認可根治 Wave6）。
     * SYSTEM_ADMIN は横断で許可する（{@code ScheduleService.checkScopeViewAccess} と同方針）。
     */
    private void checkScopeAdmin(Long userId, Long scopeId, String scopeType) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
    }

    /**
     * メンバーリストとスケジュールリストから出欠統計を構築する。
     */
    private List<AttendanceStatsResponse> buildAttendanceStats(List<Long> memberUserIds, List<Long> scheduleIds) {
        List<AttendanceStatsResponse> result = new ArrayList<>();

        for (Long userId : memberUserIds) {
            int attended = 0;
            int absent = 0;
            int partial = 0;

            for (Long scheduleId : scheduleIds) {
                java.util.Optional<ScheduleAttendanceEntity> opt =
                        attendanceRepository.findByScheduleIdAndUserId(scheduleId, userId);
                if (opt.isPresent()) {
                    AttendanceStatus status = opt.get().getStatus();
                    switch (status) {
                        case ATTENDING -> attended++;
                        case ABSENT -> absent++;
                        case PARTIAL -> partial++;
                        default -> { /* UNDECIDED: 未回答のためカウントしない */ }
                    }
                }
            }

            int totalSchedules = scheduleIds.size();
            double rate = totalSchedules > 0 ? (double) attended / totalSchedules * 100.0 : 0.0;
            result.add(new AttendanceStatsResponse(userId, totalSchedules, attended, absent, partial, rate));
        }

        return result;
    }

    /**
     * 出欠管理が有効なスケジュールかどうかを検証する。
     */
    private void validateAttendanceRequired(ScheduleEntity schedule) {
        if (!Boolean.TRUE.equals(schedule.getAttendanceRequired())) {
            throw new BusinessException(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        }
    }

    /**
     * 出欠回答の最小ロール（{@link MinResponseRole}）を enforcement する（F03.1 セキュリティ根治）。
     *
     * <p>スケジュールに {@code minResponseRole} が設定されている場合、回答者が当該スコープ
     * （TEAM / ORGANIZATION）で必要ロール以上であることをサーバ側で必須化する。これまでは
     * enum 定義・DB 保存・レスポンス出力はあったものの gate に読む箇所が無く、{@code ADMIN_ONLY} /
     * {@code MEMBER_PLUS} 制限が一般メンバー全員に開放されていた過小判定を是正する。</p>
     *
     * <ul>
     *   <li>{@code SUPPORTER_PLUS} → SUPPORTER 以上で回答可</li>
     *   <li>{@code MEMBER_PLUS} → MEMBER 以上で回答可</li>
     *   <li>{@code ADMIN_ONLY} → ADMIN / DEPUTY_ADMIN で回答可</li>
     *   <li>SYSTEM_ADMIN は横断で常に回答可</li>
     * </ul>
     *
     * <p>後方互換: {@code minResponseRole == null}（移行前データ）は従来どおり
     * メンバー全員の回答を許可し、本チェックをスキップする。PERSONAL スコープ
     * （team_id / organization_id がいずれも null）はロール概念が無いため対象外。
     * {@code accessControlService} Bean 不在のテスト構成（{@code @InjectMocks} に
     * 注入されない場合）でもスキップする（{@code getUnansweredForUserInScope} と同方針）。</p>
     */
    private void validateMinResponseRole(ScheduleEntity schedule, Long userId) {
        MinResponseRole minResponseRole = schedule.getMinResponseRole();
        if (minResponseRole == null) {
            // 後方互換: 未設定は従来どおりメンバー回答可
            return;
        }
        if (accessControlService == null) {
            // Bean 不在のテスト構成ではスキップ
            return;
        }

        // スコープ解決（PERSONAL はロール概念が無いため enforcement 対象外）
        final String scopeType;
        final Long scopeId;
        if (schedule.getTeamId() != null) {
            scopeType = "TEAM";
            scopeId = schedule.getTeamId();
        } else if (schedule.getOrganizationId() != null) {
            scopeType = "ORGANIZATION";
            scopeId = schedule.getOrganizationId();
        } else {
            return;
        }

        // SYSTEM_ADMIN は横断で常に許可
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }

        boolean allowed = switch (minResponseRole) {
            case SUPPORTER_PLUS -> accessControlService.hasRoleOrAbove(userId, scopeId, scopeType, "SUPPORTER");
            case MEMBER_PLUS -> accessControlService.hasRoleOrAbove(userId, scopeId, scopeType, "MEMBER");
            case ADMIN_ONLY -> accessControlService.isAdminOrAbove(userId, scopeId, scopeType);
        };

        // 配信＝受信権 統一（関所(3)回答）: 組織スケジュールでは、配下チームのみ所属者を
        // コンテンツの includeSupporters トグル準拠の配信母集団で救済する。配下メンバーは組織に直接
        // user_roles/memberships を持たないため hasRoleOrAbove(...) が有効ロール null で false になる
        // （実機 403 = COMMON_002 の真因）。救済の段は要求段（minResponseRole）に応じて切り替える:
        //   - MEMBER_PLUS: 配下 MEMBER を救済（includeSupporters=false＝純 SUPPORTER 除外）。
        //   - SUPPORTER_PLUS:
        //       * トグル ON（includeSupporters=true）の出欠は配下 SUPPORTER も配信母集団＝回答可のため救済する。
        //       * トグル OFF（false）は配下 MEMBER のみが母集団のため純 SUPPORTER は救済しない。
        //     いずれも isMemberOrDescendant(..., includeSupporters) が配信母集団と一致する判定を担う。
        //   - ADMIN_ONLY: 管理者要求段。配下メンバーは組織 ADMIN ではないため従来どおり不許可。
        if (!allowed && "ORGANIZATION".equals(scopeType)) {
            boolean includeSupporters = Boolean.TRUE.equals(schedule.getIncludeSupporters());
            if (minResponseRole == MinResponseRole.MEMBER_PLUS) {
                // MEMBER 要求段では純 SUPPORTER は対象外（配下 MEMBER のみ救済）。
                allowed = accessControlService.isMemberOrDescendant(userId, scopeId, scopeType, false);
            } else if (minResponseRole == MinResponseRole.SUPPORTER_PLUS) {
                // SUPPORTER 要求段はコンテンツのトグルに従って配下 SUPPORTER の救済可否を決める。
                allowed = accessControlService.isMemberOrDescendant(userId, scopeId, scopeType, includeSupporters);
            }
        }

        if (!allowed) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 出欠回答期限を検証する。
     */
    private void validateAttendanceDeadline(ScheduleEntity schedule) {
        if (schedule.getAttendanceDeadline() != null
                && LocalDateTime.now().isAfter(schedule.getAttendanceDeadline())) {
            throw new BusinessException(ScheduleErrorCode.ATTENDANCE_DEADLINE_PASSED);
        }
    }

    /**
     * コメント必須チェックを行う。commentOption が REQUIRED の場合、コメントが空なら例外をスローする。
     */
    private void validateComment(ScheduleEntity schedule, String comment) {
        if (schedule.getCommentOption() == CommentOption.REQUIRED
                && (comment == null || comment.isBlank())) {
            throw new BusinessException(ScheduleErrorCode.COMMENT_REQUIRED);
        }
    }

    /**
     * エンティティを出欠回答レスポンスDTOに変換する。
     */
    private AttendanceResponse toAttendanceResponse(ScheduleAttendanceEntity entity) {
        return new AttendanceResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getStatus().name(),
                entity.getComment(),
                entity.getRespondedAt());
    }

    private ProxyInputRecordEntity buildAndSaveProxyInputRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("SCHEDULE_ATTENDANCE")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }
}
