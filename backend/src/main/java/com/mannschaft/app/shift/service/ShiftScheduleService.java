package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
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
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
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
 * シフトスケジュールサービス（<b>トランザクション本体</b>）。シフトスケジュールのCRUD・ステータス遷移を担当する。
 *
 * <p><b>認可はここに無い（CMP-260923-0954 W6a）:</b> 認可（per-scope・存在オラクル対策・SYSTEM_ADMIN の扱い）は
 * トランザクションの外の {@link ShiftScheduleFacade} が行う。本クラスは {@code AccessControlService}・
 * {@code ScopeConcealingAccessGate} に<b>クラスごと依存しない</b>（クラスに {@code @Transactional} があると
 * 認可用の private メソッド・ラムダまで D-3T の入口に数えられ、common の認可が越境到達として凍結行を作るため）。
 * Facade は認可の前に {@link #resolveScope}（readOnly・自ドメインのみ）で scope を読み、認可の後に本クラスを
 * 呼ぶ。本クラスは書き込み tx の中で対象を<b>読み直し</b>、不在・論理削除済みなら {@code SHIFT_001}（404）を
 * 投げる（認可の後・tx の前の削除との競合＝K1）。状態の判定は認可の後の tx の中で行う。</p>
 *
 * <p>閲覧の可視性（未公開の秘匿）は認可の結果（管理者側かどうか）に依存するため、Facade が判定した
 * {@code privileged} の真偽を引数で受け取り、tx の中の最新の状態で再判定する。</p>
 *
 * <p>呼び出し元は Facade のみ（Controller は直接呼ばない）。{@code ShiftRequestService} が呼ぶ
 * package-private の {@code find*} 5 本は認可を持たない構造メソッドで、名前・可視性・引数を変えない（K3）。</p>
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
    private final ShiftAssignmentRepository assignmentRepository;
    private final ShiftPositionRepository positionRepository;
    private final ShiftMapper shiftMapper;
    private final DomainEventPublisher eventPublisher;

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

    // ═════════════════════════════════════════════════════════════════════
    // scope 解決（Facade が認可の前に呼ぶ readOnly の読み取り。戻り値は record で Entity は返さない）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * スケジュール ID から scope（所属チーム ID・公開状態）を解決する。
     * 不在・論理削除済みは {@code SHIFT_001}（404）。
     *
     * @param id スケジュールID
     * @return scope
     */
    public ShiftScheduleScope resolveScope(Long id) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        return new ShiftScheduleScope(entity.getId(), entity.getTeamId(), entity.getStatus(),
                entity.getPublishedAt() != null);
    }

    /**
     * チームのシフトスケジュール一覧を取得する。
     *
     * @param teamId     チームID
     * @param privileged 未公開も全量見てよい側（SYSTEM_ADMIN または当該チームの ADMIN 以上）なら true
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedules(Long teamId, boolean privileged) {
        List<ShiftScheduleEntity> entities = filterVisible(
                scheduleRepository.findByTeamIdOrderByStartDateDesc(teamId), privileged);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * チームのシフトスケジュール一覧を期間指定で取得する。
     *
     * @param teamId     チームID
     * @param from       期間開始
     * @param to         期間終了
     * @param privileged 未公開も全量見てよい側なら true
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedulesByPeriod(Long teamId, LocalDate from, LocalDate to,
                                                             boolean privileged) {
        List<ShiftScheduleEntity> entities = filterVisible(
                scheduleRepository.findByTeamIdAndStartDateBetweenOrderByStartDateDesc(teamId, from, to),
                privileged);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * シフトスケジュールを単体取得する。
     *
     * <p>tx の中で読み直し、未公開は管理者側（{@code privileged}）以外に 404（{@code SHIFT_001}）へ正規化する
     *（認可結果より先に 404 へ寄せるので、非メンバーが 403 と 404 を比較する存在オラクルにならない）。</p>
     *
     * @param id         スケジュールID
     * @param privileged 管理者側（SYSTEM_ADMIN または当該チームの ADMIN 以上）なら true
     * @return シフトスケジュール
     */
    public ShiftScheduleResponse getSchedule(Long id, boolean privileged) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        checkScheduleVisible(entity, privileged);
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
    public ShiftScheduleResponse updateSchedule(Long id, UpdateShiftScheduleRequest req) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);

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
        // 認可は Facade が済ませている。親行のロックは認可の後（部外者に他チームの行を掴ませない＝K6）。
        ShiftScheduleEntity entity = findScheduleForUpdateOrThrow(id);
        boolean wasLive = entity.getDeletedAt() == null;
        LocalDateTime deletedAt = LocalDateTime.now(wallClock)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // 親行のロックを保持したまま親の削除日時を確定し、子3種へ同じDB値をコピーする。
        entity.softDelete(deletedAt);
        scheduleRepository.saveAndFlush(entity);
        assignmentRepository.softDeleteByScheduleId(id);
        requestRepository.softDeleteByScheduleId(id);
        slotRepository.softDeleteByScheduleId(id);
        // 子更新の失敗時は同一トランザクションで親更新もロールバックする。

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
        ShiftScheduleStatus targetStatus = ShiftScheduleStatus.valueOf(status);
        ShiftScheduleStatus previousStatus = entity.getStatus();

        // 許可された運用フロー以外は、公開確認・状態更新・関連依頼・イベントの前に拒否する。
        boolean allowed = switch (previousStatus) {
            case DRAFT -> targetStatus == ShiftScheduleStatus.COLLECTING;
            case COLLECTING -> targetStatus == ShiftScheduleStatus.ADJUSTING;
            case ADJUSTING -> targetStatus == ShiftScheduleStatus.COLLECTING
                    || targetStatus == ShiftScheduleStatus.PUBLISHED;
            case PUBLISHED -> targetStatus == ShiftScheduleStatus.ARCHIVED;
            case ARCHIVED -> false;
        };
        if (!allowed) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

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

        // CMP-260909-1445: 公開取消が成立した場合も、公開時に積んだ PLANNED 消化を置き去りにしない。
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
        // 複製元(source)の scope 由来の認可は Facade が済ませている。tx の中で読み直す。
        ShiftScheduleEntity source = findScheduleOrThrow(id);

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
     * <p><b>認可の真の強制点</b>は {@link ShiftScheduleFacade#getScheduleSummary}（当該シフトが属する
     * チームの ADMIN/DEPUTY_ADMIN、または SYSTEM_ADMIN）。本メソッドは認可の後の tx 本体。</p>
     *
     * @param id スケジュール ID
     * @return 日付別・ポジション別の充足状況サマリー
     * @throws BusinessException スケジュールが存在しない場合（SHIFT_001）
     */
    public ShiftScheduleSummaryResponse getScheduleSummary(Long id) {
        ShiftScheduleEntity schedule = findScheduleOrThrow(id);

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
     * <p>判定は {@link ShiftScheduleVisibilityPolicy} に閉じる（設計 G-1）。管理者側かどうかは
     * Facade がチーム単位で 1 度だけ判定して渡す。</p>
     *
     * @param entities   取得済みのシフト表
     * @param privileged 未公開も全量見てよい側なら true
     * @return 閲覧者に見せてよいシフト表
     */
    private List<ShiftScheduleEntity> filterVisible(List<ShiftScheduleEntity> entities, boolean privileged) {
        if (privileged) {
            return entities;
        }
        return entities.stream()
                .filter(e -> !ShiftScheduleVisibilityPolicy
                        .classify(e.getStatus(), e.getPublishedAt()).isHidden())
                .toList();
    }

    /**
     * 未公開シフト表への単体アクセスを 404 に落とす（AC-2 / AC-7）。
     *
     * <p>403 にすると「存在するが未公開」を「存在しない ID」と区別でき、scheduleId の総当りで
     * 未公開シフト表の本数が観測できるため 404（{@code SHIFT_001}）とする。</p>
     *
     * @param entity     対象シフト表
     * @param privileged 管理者側なら true（未公開でも見える）
     * @throws BusinessException 閲覧者に対して秘匿すべき場合（SHIFT_SCHEDULE_NOT_FOUND / 404）
     */
    void checkScheduleVisible(ShiftScheduleEntity entity, boolean privileged) {
        if (privileged) {
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

    /** 子の変更と親削除の競合を防ぐ更新用取得。 */
    ShiftScheduleEntity findScheduleForUpdateOrThrow(Long id) {
        return findScheduleForUpdate(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
    }

    /** 子の不在コードへ畳む経路でも同じ親行をロックする。 */
    Optional<ShiftScheduleEntity> findScheduleForUpdate(Long id) {
        return scheduleRepository.findByIdForUpdate(id);
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
     * 開始日と終了日の整合性を検証する。
     */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ShiftErrorCode.INVALID_DATE_RANGE);
        }
    }
}
