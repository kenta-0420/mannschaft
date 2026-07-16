package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * シフトスケジュールサービス。シフトスケジュールのCRUD・ステータス遷移を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftScheduleService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ShiftRequestRepository requestRepository;
    private final ShiftPositionRepository positionRepository;
    private final ShiftMapper shiftMapper;
    private final DomainEventPublisher eventPublisher;
    private final AccessControlService accessControlService;

    /** 循環依存を避けるため @Lazy で注入する */
    @Lazy
    private final ShiftAutoAssignService autoAssignService;

    /**
     * チームのシフトスケジュール一覧を取得する。
     *
     * @param teamId チームID
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedules(Long teamId) {
        List<ShiftScheduleEntity> entities = scheduleRepository.findByTeamIdOrderByStartDateDesc(teamId);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * チームのシフトスケジュール一覧を期間指定で取得する。
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedulesByPeriod(Long teamId, LocalDate from, LocalDate to) {
        List<ShiftScheduleEntity> entities = scheduleRepository
                .findByTeamIdAndStartDateBetweenOrderByStartDateDesc(teamId, from, to);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * シフトスケジュールを単体取得する。
     *
     * @param id スケジュールID
     * @return シフトスケジュール
     */
    public ShiftScheduleResponse getSchedule(Long id) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
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
                        ? ShiftPeriodType.valueOf(req.getPeriodType()) : ShiftPeriodType.WEEKLY)
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
                req.getPeriodType() != null ? ShiftPeriodType.valueOf(req.getPeriodType()) : null,
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
        entity.softDelete();
        scheduleRepository.save(entity);
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

        switch (targetStatus) {
            case COLLECTING -> entity.startCollecting();
            case ADJUSTING -> entity.startAdjusting();
            case PUBLISHED -> {
                // 未確認の SUCCEEDED 割当実行ログがある場合は目視確認ゲートをかける
                autoAssignService.assertNoUnreviewedRuns(id);
                entity.publish(userId);
            }
            case ARCHIVED -> entity.archive();
            default -> throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

        entity = scheduleRepository.save(entity);

        // イベント発行は save() 後（AFTER_COMMIT リスナーがコミット済みデータを参照するため）
        if (targetStatus == ShiftScheduleStatus.PUBLISHED) {
            eventPublisher.publish(new ShiftPublishedEvent(entity.getId(), entity.getTeamId(), userId));
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
     * {@code @PreAuthorize("hasRole('ADMIN')")} は {@code @EnableMethodSecurity} 未有効のため
     * 実機では効かず、かつ JWT には {@code MEMBER} しか乗らないため per-scope 認可にならない。
     * 本メソッド内の {@link #checkScheduleAdminAccess} が実際の per-scope 認可
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

        // 2) 全スロットの確定アサイン件数を集計（slotId → CONFIRMED 件数）。
        //    Phase 11 事後検分 fixup（2026-05-17）: 旧実装は slot 数 N に対して N 回
        //    findAllBySlotId() を発行する N+1 クエリだった。スケジュール ID で 1 回 JOIN 取得し、
        //    Java 側で slotId でグルーピングする形に改修。
        Map<Long, Long> confirmedCountBySlot = assignmentRepository
                .findAllByScheduleId(schedule.getId()).stream()
                .filter(a -> a.getStatus() == ShiftAssignmentStatus.CONFIRMED)
                .collect(Collectors.groupingBy(
                        ShiftAssignmentEntity::getSlotId,
                        Collectors.counting()));

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

            // positionId（NULL含む）でグループ化
            Map<Long, List<ShiftSlotEntity>> byPosition = daySlots.stream()
                    .collect(Collectors.groupingBy(
                            s -> s.getPositionId(),
                            HashMap::new,
                            Collectors.toList()));

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
     * シフトスケジュールを取得する。存在しない場合は例外をスローする。
     */
    ShiftScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
    }

    /**
     * シフトスケジュールに対する管理操作の per-scope 認可を強制する。
     *
     * <p>SYSTEM_ADMIN は短絡的に許可する。それ以外は、当該スケジュールが属するチームの
     * ADMIN/DEPUTY_ADMIN でなければ {@code COMMON_002}（403）をスローする。
     * circulation ドメインの {@code CirculationService#checkScopeAdminAccess}（#1183）と同一の方針。</p>
     *
     * @param schedule 対象スケジュール
     * @param userId   操作ユーザー ID
     * @throws BusinessException 権限がない場合（COMMON_002）
     */
    void checkScheduleAdminAccess(ShiftScheduleEntity schedule, Long userId) {
        checkTeamAdminAccess(schedule.getTeamId(), userId);
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
