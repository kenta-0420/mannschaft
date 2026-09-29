package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.shift.ShiftAssignedUserIds;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleCloseReason;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * シフトスケジュールサービス。シフトスケジュールのCRUD・ステータス遷移を担当する。
 *
 * <p><b>認可の粒度（認可根治 Wave6）:</b> 全 public メソッドが操作者 {@code userId} を受け取り、
 * <b>スケジュール実体から解決した teamId</b> に対して per-scope 認可する
 *（パス変数・クエリの scope 値を鵜呑みにしないことで BOLA を封鎖する）。</p>
 *
 * <ul>
 *   <li><b>参照</b>（{@code listSchedules} / {@code listSchedulesByPeriod} / {@code getSchedule}）:
 *       当該チームのメンバー、ただし SUPPORTER は不可（{@link #checkTeamReadAccess}）。
 *       シフト表の閲覧は一般メンバーの日常操作であるため管理者に限定しない。</li>
 *   <li><b>更新・状態遷移・複製・サマリ</b>: ADMIN/DEPUTY_ADMIN 以上（SYSTEM_ADMIN 短絡。
 *       {@link #checkScheduleAdminAccess}）。</li>
 * </ul>
 *
 * <p><b>存在オラクル対策（CMP-260917-1137）:</b> scheduleId は連番で総当りが容易なため、
 * 「越境（他チーム/他テナント＝当該チームに所属すらしていない）」場合は
 * {@link #findScheduleOrThrow} の不在応答と<b>完全に同一</b>の {@code SHIFT_001}（404）へ畳む。
 * 403 のまま残すのは「同一チーム内で権限が足りないだけ」の場合のみ
 *（例: 一般メンバーが管理操作を叩く／SUPPORTER が参照する）。この区別は
 * {@link #checkScheduleAdminAccess} / {@link #checkScheduleReadAccess} が
 * 所属・ロールの有無を先に見てから判定することで実現する
 * （村ドメインの {@code VillageAccessGate} と同じ作法。詳しい理由は各メソッドの Javadoc を参照）。
 * <b>判定順（CMP-260923-1641 是正・Codex 検分指摘）:</b> {@code isAdminOrAbove} を
 * {@code isMember} より先に評価する。{@code isMember} は {@code memberships} のみを見るのに
 * 対し {@code isAdminOrAbove} は {@code user_roles}／{@code memberships} の2系統で有効ロールを
 * 解決するため、{@code isMember} を先に置くと {@code user_roles} にしか ADMIN/DEPUTY_ADMIN を
 * 持たない利用者（開発DB実測: チーム管理者ロール609件中2件）を越境と誤判定してしまう。
 * {@link #checkTeamReadAccess} / {@link #checkTeamAdminAccess}
 *（{@code listSchedules} 系・{@code createSchedule} の teamId 直接指定経路）は、
 * scheduleId を推測する攻撃の対象にならないため対象外とし、従来どおり 403 のままとする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftScheduleService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftChangeRequestRepository changeRequestRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftRequestRepository requestRepository;
    private final ShiftPositionRepository positionRepository;
    private final ShiftMapper shiftMapper;
    private final DomainEventPublisher eventPublisher;
    private final AccessControlService accessControlService;

    /** 循環依存を避けるため @Lazy で注入する */
    @Lazy
    private final ShiftAutoAssignService autoAssignService;

    /**
     * 業務ローカル時刻の壁時計（{@code ClockConfig#wallClock}）。
     *
     * <p>ARCHIVED 遷移時に OPEN 変更依頼を一括 WITHDRAWN 化する JPQL UPDATE へ渡す時刻に使う。
     * 対象列 {@code shift_change_requests.updated_at} は JVM 既定ゾーン基準の壁時計として
     * 書かれた {@code LocalDateTime} 列なので、UTC 固定の既定 {@code Clock}
     * （{@code ClockConfig#utcClock}、{@code @Primary}）ではオフセット分（JST なら 9 時間）ずれる。
     * そのため {@code @Qualifier("wallClock")} で明示的に壁時計を選ぶ
     * （金型: {@code BatchJobLogService} / {@code AdReportService}）。</p>
     *
     * <p>引数なしの {@code LocalDateTime.now()} を使わないのは、番人
     * {@code DateTimeAndZoneGuardTest}（CMP-023）が禁じているため。凍結台帳は<b>返済対象の
     * 技術負債</b>であって件数を積み増す先ではない。隣の {@code ShiftAutoArchiveBatchService}
     * が引数なし {@code now()} を使っているのは台帳登録済みの既存負債であり、模範ではない。</p>
     */
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * チームのシフトスケジュール一覧を取得する。
     *
     * @param teamId チームID
     * @param userId 操作者ユーザーID（認可チェック用）
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedules(Long teamId, Long userId) {
        checkTeamReadAccess(teamId, userId);
        List<ShiftScheduleEntity> entities = filterVisible(
                scheduleRepository.findByTeamIdOrderByStartDateDesc(teamId), teamId, userId);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * チームのシフトスケジュール一覧を期間指定で取得する。
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @param userId 操作者ユーザーID（認可チェック用）
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedulesByPeriod(Long teamId, LocalDate from, LocalDate to, Long userId) {
        checkTeamReadAccess(teamId, userId);
        List<ShiftScheduleEntity> entities = filterVisible(
                scheduleRepository.findByTeamIdAndStartDateBetweenOrderByStartDateDesc(teamId, from, to),
                teamId, userId);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * シフトスケジュールを単体取得する。
     *
     * <p>scope はパス変数でなく <b>スケジュール実体の teamId</b> で解決してから認可する
     * （呼び出し側から渡された scope 値を鵜呑みにしないことで BOLA を封鎖する）。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID（認可チェック用）
     * @return シフトスケジュール
     */
    public ShiftScheduleResponse getSchedule(Long id, Long userId) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        // 未公開は認可結果より先に 404 へ正規化する。認可を先にすると、非メンバーが
        // 実在 ID の 403 と非存在 ID の 404 を比較でき、未公開シフト表の存在オラクルになる。
        checkScheduleVisible(entity, userId);
        // CMP-260917-1137: teamId 直接指定の checkTeamReadAccess ではなく、越境を 404 に畳む
        // checkScheduleReadAccess を使う（scheduleId 総当りでの存在オラクル対策）。
        checkScheduleReadAccess(entity, userId);
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを作成する。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse createSchedule(Long teamId, CreateShiftScheduleRequest req, Long userId) {
        checkTeamAdminAccess(teamId, userId);
        validateDateRange(req.getStartDate(), req.getEndDate());

        ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(req.getTitle())
                .periodType(req.getPeriodType() != null
                        ? EnumInputParser.parse(ShiftPeriodType.class, req.getPeriodType(), "periodType") : ShiftPeriodType.WEEKLY)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .requestDeadline(req.getRequestDeadline())
                .note(req.getNote())
                .createdBy(userId)
                .build();

        entity = scheduleRepository.save(entity);

        log.info("シフトスケジュール作成: id={}, teamId={}, title={}", entity.getId(), teamId, entity.getTitle());
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを更新する。
     *
     * @param id  スケジュールID
     * @param req 更新リクエスト
     * @return 更新されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse updateSchedule(Long id, UpdateShiftScheduleRequest req, Long userId) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        checkScheduleAdminAccess(entity, userId);

        // 日付整合性検証（更新後の組み合わせで確認）
        LocalDate startDate = req.getStartDate() != null ? req.getStartDate() : entity.getStartDate();
        LocalDate endDate = req.getEndDate() != null ? req.getEndDate() : entity.getEndDate();
        validateDateRange(startDate, endDate);

        // managed entity を直接ミューテート（toBuilder().build() でなくドメインメソッドで更新）。
        // ShiftScheduleEntity は @Builder(toBuilder=true) / @SuperBuilder でない / BaseEntity継承(自前id無)
        // の3条件が揃うため、toBuilder().build()→save では id=null の新インスタンスが生成され
        // UPDATE でなく INSERT が走る行重複バグになる。
        entity.applyUpdate(
                req.getTitle(),
                req.getPeriodType() != null ? EnumInputParser.parse(ShiftPeriodType.class, req.getPeriodType(), "periodType") : null,
                req.getStartDate(),
                req.getEndDate(),
                req.getRequestDeadline(),
                req.getNote()
        );

        scheduleRepository.save(entity);

        log.info("シフトスケジュール更新: id={}", id);
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを論理削除する。
     *
     * @param id スケジュールID
     */
    @Transactional
    public void deleteSchedule(Long id, Long userId) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        checkScheduleAdminAccess(entity, userId);
        boolean wasLive = entity.getDeletedAt() == null;
        entity.softDelete();
        scheduleRepository.save(entity);

        // CMP-260909-1445: 論理削除でもシフト予算の PLANNED 消化を取り消す。
        // 取消理由 enum に SHIFT_DELETED が用意されているとおり、削除で取り消すのが元々の設計意図。
        // 消化を残すと当該 allocation が SHIFT_BUDGET_012 で恒久的に削除不能になる。
        // 既に削除済みの行に対する再削除ではイベントを出さない（冪等。実処理側の
        // cancelAllForShift も PLANNED のみを対象とするため二重減算はしないが、
        // 意味の無いイベントを流さないことを発行側でも担保する）。
        if (wasLive) {
            eventPublisher.publish(new ShiftScheduleClosedEvent(
                    entity.getId(), entity.getTeamId(), userId, ShiftScheduleCloseReason.DELETED));
        }
        log.info("シフトスケジュール削除: id={}", id);
    }

    /**
     * シフトスケジュールのステータスを遷移する。
     *
     * @param id     スケジュールID
     * @param status 遷移先ステータス
     * @param userId 操作者ID
     * @return 更新されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse transitionStatus(Long id, String status, Long userId) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        checkScheduleAdminAccess(entity, userId);
        ShiftScheduleStatus targetStatus = ShiftScheduleStatus.valueOf(status);
        ShiftScheduleStatus previousStatus = entity.getStatus();

        switch (targetStatus) {
            case COLLECTING -> entity.startCollecting();
            case ADJUSTING -> entity.startAdjusting();
            case PUBLISHED -> {
                // 未確認の SUCCEEDED 割当実行ログがある場合は目視確認ゲートをかける
                autoAssignService.assertNoUnreviewedRuns(id);
                entity.publish(userId);
            }
            case ARCHIVED -> {
                entity.archive();
                // CMP-260909-1445: ARCHIVED 遷移の副作用はバッチ経路（ShiftAutoArchiveBatchService）と
                // 揃える。OPEN の変更依頼はアーカイブ済みシフトに対して審査しようがないため取り下げる。
                int withdrawn = changeRequestRepository.withdrawOpenRequestsByScheduleId(
                        entity.getId(), LocalDateTime.now(wallClock));
                if (withdrawn > 0) {
                    log.info("OPEN 変更依頼を自動 WITHDRAWN: scheduleId={}, 件数={}", entity.getId(), withdrawn);
                }
            }
            default -> throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

        entity = scheduleRepository.save(entity);

        // イベント発行は save() 後（AFTER_COMMIT リスナーがコミット済みデータを参照するため）
        if (targetStatus == ShiftScheduleStatus.PUBLISHED) {
            eventPublisher.publish(new ShiftPublishedEvent(
                    entity.getId(), entity.getTeamId(), userId, entity.getPublishedAt()));
        }

        // CMP-260909-1445: 手動アーカイブでも ShiftArchivedEvent を発行する。
        // 是正前は発行元がバッチ 1 箇所しか無く、UI/API 経由でアーカイブすると
        // 予算消化の取消（ShiftBudgetConsumptionCancelListener）も Todo の自動 CANCELLED 化
        // （ShiftArchivedToTodoCancelListener）も一切走らなかった。
        // archivedByUserId は手動経路では操作者を載せる（バッチは null）。
        if (targetStatus == ShiftScheduleStatus.ARCHIVED) {
            eventPublisher.publish(new ShiftArchivedEvent(
                    entity.getId(), entity.getTeamId(), userId));
        }

        // CMP-260909-1445: PUBLISHED からの後戻り遷移（公開取消）も同型の穴だった。
        // ShiftScheduleEntity の遷移メソッドはガードを持たず status を無条件に上書きするため
        // この後戻りは実際に成立する。公開時に積んだ PLANNED 消化を置き去りにしない。
        if (previousStatus == ShiftScheduleStatus.PUBLISHED
                && (targetStatus == ShiftScheduleStatus.COLLECTING
                        || targetStatus == ShiftScheduleStatus.ADJUSTING)) {
            eventPublisher.publish(new ShiftScheduleClosedEvent(
                    entity.getId(), entity.getTeamId(), userId, ShiftScheduleCloseReason.UNPUBLISHED));
        }

        log.info("シフトスケジュールステータス遷移: id={}, status={}", id, targetStatus);
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを複製する。
     *
     * @param id     複製元ID
     * @param userId 作成者ID
     * @return 複製されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse duplicateSchedule(Long id, Long userId) {
        ShiftScheduleEntity source = findScheduleOrThrow(id);
        // BOLA是正（認可根治 Wave3-B6）: 複製元(source)のscope由来で認可する。shift ドメイン内に
        // duplicateSchedule の内部呼び出し元は存在しない（grep 確認済み）ため、この共有メソッド自体に
        // 認可を敷設してよい（schedule ドメインの ScheduleService.duplicateSchedule とは事情が異なる）。
        checkScheduleAdminAccess(source, userId);

        ShiftScheduleEntity duplicate = source.toBuilder()
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(userId)
                .publishedAt(null)
                .publishedBy(null)
                .isReminderSent(false)
                .isLowSubmissionAlerted(false)
                .lastAutoTransitionAt(null)
                .deletedAt(null)
                .build();

        duplicate = scheduleRepository.save(duplicate);
        log.info("シフトスケジュール複製: sourceId={}, newId={}", id, duplicate.getId());
        return shiftMapper.toScheduleResponse(duplicate);
    }

    /**
     * シフトスケジュールの「日付 × ポジション」充足状況サマリーを取得する。
     *
     * <p>管理者のシフト調整画面の概観表示で使用する。スロット・確定アサイン・希望提出を
     * それぞれ集計し、未充足の箇所を一望できるマトリクスとして返す。</p>
     *
     * <p><b>認可の真の強制点（Track2 第二陣 / 2026-05-29）</b>: コントローラーの
     * {@code @PreAuthorize("hasRole('ADMIN')")} は、JWT には {@code MEMBER} しか乗らないため
     * per-scope 認可にならない。本メソッド内の {@link #checkScheduleAdminAccess} が実際の per-scope 認可
     * （当該シフトが属するチームの ADMIN/DEPUTY_ADMIN、または SYSTEM_ADMIN）を強制する。</p>
     *
     * @param id     スケジュール ID
     * @param userId 操作ユーザー ID（認可チェック用）
     * @return 日付別・ポジション別の充足状況サマリー
     * @throws BusinessException スケジュールが存在しない場合 / 権限がない場合（COMMON_002）
     */
    public ShiftScheduleSummaryResponse getScheduleSummary(Long id, Long userId) {
        ShiftScheduleEntity schedule = findScheduleOrThrow(id);
        checkScheduleAdminAccess(schedule, userId);

        // 1) スロット一覧（日付・開始時刻昇順）を取得
        List<ShiftSlotEntity> slots = slotRepository
                .findByScheduleIdOrderBySlotDateAscStartTimeAsc(schedule.getId());

        // 2) 全スロットの割当人数を集計（slotId → 割当人数）。
        //    CMP-260908-2117: 旧実装は shift_assignments の CONFIRMED 件数を数えていたため、
        //    手動割当（JSON 列にしか書かれない）が充足数に一切反映されず、
        //    実際には埋まっている枠が管理者のサマリーで「未充足」に見えていた。
        //    割当の正本である slots.assigned_user_ids から数える。
        //    副次効果として shift_assignments への 1 クエリが不要になる（N+1 回避は維持）。
        Map<Long, Long> confirmedCountBySlot = slots.stream()
                .collect(Collectors.toMap(
                        ShiftSlotEntity::getId,
                        s -> (long) ShiftAssignedUserIds.parse(s.getAssignedUserIds()).size(),
                        (a, b) -> a));

        // 3) スケジュール全希望を取得（後で日付ごとに分配）
        List<ShiftRequestEntity> allRequests = requestRepository
                .findByScheduleIdOrderBySlotDateAsc(schedule.getId());

        // 4) ポジション名解決用マップ（teamId 内の全ポジション）
        Map<Long, String> positionNameMap = positionRepository
                .findByTeamIdOrderByDisplayOrderAsc(schedule.getTeamId()).stream()
                .collect(Collectors.toMap(ShiftPositionEntity::getId, ShiftPositionEntity::getName));

        // 5) 日付ごとにスロットをグループ化
        Map<LocalDate, List<ShiftSlotEntity>> slotsByDate = slots.stream()
                .collect(Collectors.groupingBy(ShiftSlotEntity::getSlotDate));

        // 6) 日付ごとの希望件数（slot_date 単位、preference 種別を問わない延べ件数）
        Map<LocalDate, Long> requestCountByDate = allRequests.stream()
                .collect(Collectors.groupingBy(ShiftRequestEntity::getSlotDate, Collectors.counting()));

        // 7) 日付昇順で DateSummary を組み立てる
        List<LocalDate> dates = slotsByDate.keySet().stream().sorted().toList();
        List<ShiftScheduleSummaryResponse.DateSummary> dateSummaries = new ArrayList<>();
        for (LocalDate date : dates) {
            List<ShiftSlotEntity> daySlots = slotsByDate.get(date);

            // positionId（NULL含む）でグループ化。
            //
            // CMP-260908-2117: ここはかつて Collectors.groupingBy(s -> s.getPositionId(), HashMap::new, ...)
            // だったが、**positionId が NULL の枠が 1 つでもあるとサマリー API が 500 になる**バグがあった。
            // groupingBy は分類関数の戻り値を必ず Objects.requireNonNull で検査するため、
            // マップ実装に HashMap::new を渡しても NULL キーは通らない（「NULL含む」という
            // 元コードの意図は成立していなかった）。ポジション未設定の枠は実運用で普通に作れる
            //（createSlot の positionId は任意）。
            //
            // 既存の単体テストが緑だったのは、フィクスチャが常に positionId を設定していたためで、
            // 本 CMP の統合テスト（ポジション未設定の枠）が初めてこれを暴いた。
            //
            // NULL キーを実際に受けられるよう手動でグループ化する。LinkedHashMap にするのは
            // 出力順を枠の並び（日付・開始時刻昇順）で決定的にするため（HashMap ではハッシュ順に依存していた）。
            Map<Long, List<ShiftSlotEntity>> byPosition = new LinkedHashMap<>();
            for (ShiftSlotEntity daySlot : daySlots) {
                byPosition.computeIfAbsent(daySlot.getPositionId(), k -> new ArrayList<>()).add(daySlot);
            }

            List<ShiftScheduleSummaryResponse.PositionSummary> positionSummaries = byPosition.entrySet().stream()
                    .map(e -> {
                        Long positionId = e.getKey();
                        List<ShiftSlotEntity> positionSlots = e.getValue();
                        int required = positionSlots.stream()
                                .mapToInt(s -> s.getRequiredCount() != null ? s.getRequiredCount() : 0)
                                .sum();
                        long confirmed = positionSlots.stream()
                                .mapToLong(s -> confirmedCountBySlot.getOrDefault(s.getId(), 0L))
                                .sum();
                        // 希望は slot 単位で割り出すのが本来理想だが、現状の shift_requests は
                        // slot_id NULL かつ slot_date 単位で提出されるユースケースが多いため、
                        // ポジション単位の希望集計は「ポジション指定なし」枠を含む day-level の
                        // 延べ件数を再掲する形にとどめる（将来 slot_id 必須化されたら絞り込み導入）。
                        return ShiftScheduleSummaryResponse.PositionSummary.builder()
                                .positionId(positionId)
                                .positionName(positionId != null
                                        ? positionNameMap.getOrDefault(positionId, "(不明)")
                                        : null)
                                .required(required)
                                .confirmed((int) confirmed)
                                .requested(0) // ポジション単位の希望集計は v1 では未対応（day-level に集約）
                                .build();
                    })
                    .sorted(Comparator.comparing(
                            ShiftScheduleSummaryResponse.PositionSummary::getPositionId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            int totalRequired = positionSummaries.stream()
                    .mapToInt(ShiftScheduleSummaryResponse.PositionSummary::getRequired).sum();
            int totalConfirmed = positionSummaries.stream()
                    .mapToInt(ShiftScheduleSummaryResponse.PositionSummary::getConfirmed).sum();
            int totalRequested = (int) (long) requestCountByDate.getOrDefault(date, 0L);

            dateSummaries.add(ShiftScheduleSummaryResponse.DateSummary.builder()
                    .date(date)
                    .byPosition(positionSummaries)
                    .totalRequired(totalRequired)
                    .totalConfirmed(totalConfirmed)
                    .totalRequested(totalRequested)
                    .build());
        }

        return ShiftScheduleSummaryResponse.builder()
                .scheduleId(schedule.getId())
                .summaryByDate(dateSummaries)
                .build();
    }

    /**
     * 一覧から、閲覧者に対して存在ごと秘匿すべきシフト表を除外する（AC-1 / AC-7）。
     *
     * <p>判定は {@link ShiftScheduleVisibilityPolicy} に閉じる（設計 G-1）。
     * 管理者判定はチーム単位で 1 度だけ行う。</p>
     *
     * @param entities 取得済みのシフト表
     * @param teamId   対象チーム ID
     * @param userId   閲覧者ユーザー ID
     * @return 閲覧者に見せてよいシフト表
     */
    private List<ShiftScheduleEntity> filterVisible(List<ShiftScheduleEntity> entities, Long teamId, Long userId) {
        if (isPrivilegedViewer(teamId, userId)) {
            return entities;
        }
        return entities.stream()
                .filter(e -> !ShiftScheduleVisibilityPolicy
                        .classify(e.getStatus(), e.getPublishedAt()).isHidden())
                .toList();
    }

    /**
     * 閲覧者が「未公開シフト表も全量見てよい側」かを判定する。
     *
     * <p>SYSTEM_ADMIN 短絡を必ず最初に評価する。親組織 ADMIN・配下ツリー救済は含めない
     *（シフト表は TEAM スコープ専用であり、同ドメインの書込系認可も配下概念を持たないため）。
     * 例外を投げる {@code checkAdminOrAbove} でなく真偽を返す {@code isAdminOrAbove} を使うのは、
     * フィルタ判定で例外を握り潰す実装を誘発しないため。</p>
     *
     * @param teamId 対象チーム ID
     * @param userId 閲覧者ユーザー ID
     * @return 管理者側なら true
     */
    private boolean isPrivilegedViewer(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        return teamId != null && accessControlService.isAdminOrAbove(userId, teamId, "TEAM");
    }

    /**
     * 未公開シフト表への単体アクセスを 404 に落とす（AC-2 / AC-7）。
     *
     * <p>403 にすると「存在するが未公開」を「存在しない ID」と区別でき、scheduleId の総当りで
     * 未公開シフト表の本数が観測できるため 404（{@code SHIFT_001}）とする。</p>
     *
     * @param entity 対象シフト表
     * @param userId 閲覧者ユーザー ID
     * @throws BusinessException 閲覧者に対して秘匿すべき場合（SHIFT_SCHEDULE_NOT_FOUND / 404）
     */
    void checkScheduleVisible(ShiftScheduleEntity entity, Long userId) {
        if (isPrivilegedViewer(entity.getTeamId(), userId)) {
            return;
        }
        if (ShiftScheduleVisibilityPolicy.classify(entity.getStatus(), entity.getPublishedAt()).isHidden()) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
    }

    /**
     * シフトスケジュールを取得する。存在しない場合は例外をスローする。
     */
    ShiftScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
    }

    /**
     * スケジュールを引く（論理削除済みは {@code @SQLRestriction} により空）。
     * 子リソース側が「親の不在」を子の不在コードで返すために使う（CMP-260923-0954）。
     */
    Optional<ShiftScheduleEntity> findSchedule(Long id) {
        return scheduleRepository.findById(id);
    }

    /**
     * 与えた ID 集合のうち、現に生存している（論理削除されていない）スケジュール ID を返す
     *（案C / CMP-260917-1136）。{@code GET /shifts/my/requests} が親の生死を判定するための
     * バッチ問い合わせ。{@code findAllById} は {@code @SQLRestriction} を尊重するため、
     * 論理削除済みの ID は戻り値に含まれない。
     *
     * @param scheduleIds 判定対象のスケジュール ID 集合
     * @return 生存しているスケジュール ID 集合
     */
    java.util.Set<Long> findExistingScheduleIds(java.util.Collection<Long> scheduleIds) {
        if (scheduleIds.isEmpty()) {
            return java.util.Set.of();
        }
        return scheduleRepository.findAllById(scheduleIds).stream()
                .map(ShiftScheduleEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * シフトスケジュールに対する管理操作の per-scope 認可を強制する。
     *
     * <p>SYSTEM_ADMIN は短絡的に許可する。<b>次に {@code isAdminOrAbove} を判定する</b>
     * （CMP-260923-1641 是正・Codex 検分指摘）。{@code isAdminOrAbove} は有効ロールを
     * user_roles と memberships の2系統で解決するのに対し、{@code isMember} は memberships のみを
     * 見る。そのため user_roles に ADMIN/DEPUTY_ADMIN ロールを持ちながら在籍中の memberships 行を
     * 持たない利用者（開発DB実測: チーム管理者ロール609件中2件）を、{@code isMember} 先判定だと
     * 「越境」と誤判定して不在応答（404）へ弾いてしまう回帰が生じる。{@code isAdminOrAbove} で
     * 先に許可した上で、それでも通らない場合にだけ<b>当該スケジュールが属するチームの
     * メンバーかどうか</b>を見る。所属すらしていない（越境／他テナント）場合は、
     * scheduleId 総当りでの存在オラクル（CMP-260917-1137）を塞ぐため
     * {@link #findScheduleOrThrow} の不在応答と同一の {@link ShiftErrorCode#SHIFT_SCHEDULE_NOT_FOUND}
     * （404）へ畳む。所属した上で ADMIN/DEPUTY_ADMIN でないだけ（同一チーム内の権限不足）の場合は、
     * 「権限が足りない」と気づけるよう従来どおり {@code COMMON_002}（403）を投げる。
     * circulation ドメインの {@code CirculationService#checkScopeAdminAccess}（#1183）と同一の方針。</p>
     *
     * <h3>なぜ 404 と 403 を作り分けるのか（村ドメインの前例に倣う）</h3>
     * <p>{@code VillageAccessGate} が既に「非村人が任意の村 ID を叩くと応答の違いそのものが
     * 存在を漏らす」問題を解決済みであり、本ドメインでも同じ理屈が越境にだけ未適用だった
     * （未公開シフト表は {@link #checkScheduleVisible} で既に 404 化済み）。今回それを揃える。
     * 新しい専用エラーコードは作らない。専用コードを作ること自体が
     * 「非公開です／越境です」という別の存在の手掛かりになるため、
     * <b>不在時と文字列まで完全一致するコード</b>を再利用する。</p>
     *
     * @param schedule 対象スケジュール
     * @param userId   操作ユーザー ID
     * @throws BusinessException 越境の場合（{@code SHIFT_001}／404）、
     *                           同一チーム内で権限が足りない場合（{@code COMMON_002}／403）
     */
    void checkScheduleAdminAccess(ShiftScheduleEntity schedule, Long userId) {
        // 認可根治 Wave6: 判定内容は checkTeamAdminAccess と同一だが、ArchUnit 認可番人の
        // 委譲追跡が 2 ホップまで（MAX_DELEGATION_DEPTH=2）のため、AccessControlService を
        // 本メソッドから直接呼んでフラット化してある（委譲すると番人から見えなくなる）。
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long teamId = schedule.getTeamId();
        // CMP-260923-1641: isAdminOrAbove を isMember より先に判定する（user_roles のみに
        // ADMIN/DEPUTY_ADMIN を持つ利用者を越境と誤判定しないため。ShiftAvailabilityService
        // #checkTeamAccess と同一方針）。
        if (accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")) {
            // 越境（他チーム／無所属）: 存在自体を隠すべき側。不在時と完全同一のコードを投げる。
            throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
        // 同一チーム内の権限不足（メンバーだが ADMIN/DEPUTY_ADMIN ではない）: 隠す必要が無い側。
        // 従来どおり 403。
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    /**
     * シフトスケジュール単体参照の per-scope 認可を強制する（{@link #getSchedule} 専用）。
     *
     * <p>{@link #checkTeamReadAccess}（{@code listSchedules} 系の teamId 直接指定用）とは異なり、
     * <b>越境（当該チームに所属すらしていない）を 404 へ畳む</b>。scheduleId は連番で総当りが容易な
     * ため、{@code listSchedules} のように呼び出し元が明示した teamId への 403 とは異なり、
     * scheduleId から teamId を逆引きする本経路では 403/404 の違いがそのまま
     * 「この scheduleId は実在する」という答えになってしまう（CMP-260917-1137）。
     * SUPPORTER（同一チーム内の権限不足）は隠す必要が無いため 403 のまま残す。</p>
     *
     * <p><b>判定順（CMP-260923-1641 是正・Codex 検分指摘）:</b> {@code isAdminOrAbove} を
     * {@code isMember} より先に評価する。{@code isMember} は memberships のみを見るため、
     * user_roles にしか ADMIN/DEPUTY_ADMIN ロールを持たない利用者（開発DB実測: チーム管理者ロール
     * 609件中2件）を、isMember 先判定だと越境と誤判定して不在応答（404）へ弾いてしまう。
     * 「読める条件（admin ロール or メンバーかつ非 SUPPORTER）」を先に評価し、どちらも満たさない
     * 場合だけ、所属の有無で 404/403 を作り分ける
     *（{@code ShiftAvailabilityService#checkTeamAccess} と同一方針）。</p>
     *
     * @param entity 対象スケジュール
     * @param userId 閲覧者ユーザー ID
     * @throws BusinessException 越境の場合（{@code SHIFT_001}／404）、
     *                           同一チーム内で SUPPORTER の場合（{@code COMMON_002}／403）
     */
    private void checkScheduleReadAccess(ShiftScheduleEntity entity, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long teamId = entity.getTeamId();
        // CMP-260923-1641: isAdminOrAbove を isMember より先に判定する（user_roles のみに
        // ADMIN/DEPUTY_ADMIN を持つ利用者を越境と誤判定しないため）。
        if (accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
        if (accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * シフトスケジュールの参照認可（当該チームのメンバー。ただし SUPPORTER は不可）。
     *
     * <p>SYSTEM_ADMIN は短絡的に許可する。粒度を「管理者」でなく「メンバー」としているのは、
     * シフト表の閲覧が一般メンバーの日常的な利用であるため。SUPPORTER を除外するのは
     * {@code ShiftSlotService#checkScheduleReadAccess} / {@code ShiftPdfService} と同一方針
     *（PDF で SUPPORTER に伏せている情報を生 API から取れては意味がないため）。</p>
     *
     * @param teamId 対象チームID
     * @param userId 操作者ユーザーID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkTeamReadAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * チーム ID 直接指定での管理操作 per-scope 認可（認可根治 Wave3-B6）。
     *
     * <p>{@link #createSchedule} はエンティティ未生成の時点（path 由来 teamId のみ）で
     * 認可が必要なため、{@link #checkScheduleAdminAccess} と同じ判定ロジックを teamId 直接指定で
     * 呼べるように分離した。SYSTEM_ADMIN は短絡的に許可する。</p>
     *
     * @param teamId 対象チームID
     * @param userId 操作ユーザーID
     * @throws BusinessException 権限がない場合（COMMON_002）
     */
    private void checkTeamAdminAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
    }

    /**
     * 開始日と終了日の整合性を検証する。
     */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ShiftErrorCode.INVALID_DATE_RANGE);
        }
    }
}
