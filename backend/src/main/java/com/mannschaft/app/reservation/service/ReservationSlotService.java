package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 予約スロットサービス。チームが提供する予約時間枠のCRUD・状態管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationSlotService {

    private final ReservationSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;
    /** 機能B: 空き枠一覧から予約不可枠に該当する slot を除外するためのブロック時間参照。 */
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** 機能B: 予約不可枠の overlap 判定を共有する単一ユーティリティ（§5.B）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    /** F03.4.2: 枠のライン軸（lineId）検証用のライン参照（同一 reservation ドメイン内）。 */
    private final com.mannschaft.app.reservation.repository.ReservationLineRepository lineRepository;
    /** 過去日判定の基準時刻（チーム TZ は将来拡張。現状はシステム既定 Clock）。テストは固定 Clock を注入する。 */
    private final Clock clock;

    /**
     * スロット削除ガードで「予約が紐づいている」と見なす active ステータス。
     * PENDING / CONFIRMED は将来の来店が期待されており、枠を消すとオーファン化する。
     * CANCELLED / COMPLETED / NO_SHOW は終端状態のため削除を妨げない。
     */
    private static final List<ReservationStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    /**
     * チームのスロット一覧を日付範囲で取得する。
     *
     * @param teamId チームID
     * @param from   開始日
     * @param to     終了日
     * @return スロットレスポンスリスト
     */
    public List<ReservationSlotResponse> listSlots(Long teamId, LocalDate from, LocalDate to) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, from, to);
        return enrichLineNames(reservationMapper.toSlotResponseList(slots));
    }

    /**
     * チームの利用可能なスロット一覧を日付範囲で取得する。
     *
     * @param teamId チームID
     * @param from   開始日
     * @param to     終了日
     * @return 利用可能なスロットレスポンスリスト
     */
    public List<ReservationSlotResponse> listAvailableSlots(Long teamId, LocalDate from, LocalDate to) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, SlotStatus.AVAILABLE, from, to);

        // 機能B（§5.B）: 予約不可枠に該当する slot を空き枠一覧から除外する。
        // 判定は createReservation / グリッドと共有の単一 overlap ユーティリティを用いる（別実装厳禁）。
        List<ReservationBlockedTimeEntity> blocks =
                blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(teamId, from, to);
        List<ReservationSlotEntity> visible = blocks.isEmpty()
                ? slots
                : slots.stream()
                        .filter(slot -> !unavailabilityChecker.isBlockedByAny(slot, blocks))
                        .toList();

        return enrichLineNames(reservationMapper.toSlotResponseList(visible));
    }

    /**
     * スロット詳細を取得する。
     *
     * @param teamId チームID
     * @param slotId スロットID
     * @return スロットレスポンス
     */
    public ReservationSlotResponse getSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
        return enrichLineNames(List.of(reservationMapper.toSlotResponse(entity))).get(0);
    }

    /**
     * スロットを作成する。
     *
     * @param teamId    チームID
     * @param request   作成リクエスト
     * @param createdBy 作成者ユーザーID
     * @return 作成されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse createSlot(Long teamId, CreateSlotRequest request, Long createdBy) {
        // ③ 過去日の枠作成禁止（注入 Clock 基準で当日以降のみ許可）。LocalDate.now() 直書きは CI 破壊地雷のため禁止。
        if (request.getSlotDate() != null && request.getSlotDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(ReservationErrorCode.PAST_DATE_SLOT);
        }
        validateTimeRange(request.getStartTime(), request.getEndTime());
        // F03.4.2: ライン軸（lineId・任意）。指定時は当該チームのラインであることを検証する
        // （FK は「行の存在」しか守れず他チームのラインを掴めるため、チーム帰属はアプリ層で担保する）。
        validateLineBelongsToTeam(teamId, request.getLineId());

        ReservationSlotEntity entity = ReservationSlotEntity.builder()
                .teamId(teamId)
                .staffUserId(request.getStaffUserId())
                .lineId(request.getLineId())
                .title(request.getTitle())
                .slotDate(request.getSlotDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                // 定員。未指定（null）は既定 1（＝1:1 指名）。builder に null を渡すと @Builder.Default を
                // 上書きして NULL 挿入になるため、必ず normalizeCapacity で 1 以上へ正規化する。
                .capacity(normalizeCapacity(request.getCapacity()))
                .price(request.getPrice())
                .note(request.getNote())
                // 枠単位の承認モード上書き。null = チーム既定に従う（継承）。
                .approvalMode(request.getApprovalMode())
                .createdBy(createdBy)
                .build();

        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロット作成: teamId={}, slotId={}, date={}", teamId, saved.getId(), saved.getSlotDate());
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを更新する。
     *
     * @param teamId  チームID
     * @param slotId  スロットID
     * @param request 更新リクエスト
     * @return 更新されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse updateSlot(Long teamId, Long slotId, UpdateSlotRequest request) {
        // findSlotOrThrow が返す managed entity を直接 in-place 変更する。
        // 以前は entity.toBuilder().build() の detached コピーを save していたため、
        // merge の戻り値（新値）はレスポンスに乗るものの、同一トランザクションの flush 時に
        // 未変更の元 managed entity が勝ち、DB は旧値のまま残るバグ（実機E2E #1665）があった。
        // closeSlot / reopenSlot と同じく managed entity をドメインメソッドで変更し、
        // dirty checking（＋明示 save）で確実に永続化する。
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);

        if (request.getStaffUserId() != null) {
            entity.changeStaffUser(request.getStaffUserId());
        }
        // F03.4.2: ライン軸の変更（null = 据え置き・部分更新）。チーム帰属を検証する。
        if (request.getLineId() != null) {
            validateLineBelongsToTeam(teamId, request.getLineId());
            entity.changeLine(request.getLineId());
        }
        if (request.getTitle() != null) {
            entity.changeTitle(request.getTitle());
        }
        if (request.getSlotDate() != null) {
            entity.changeSlotDate(request.getSlotDate());
        }
        if (request.getStartTime() != null && request.getEndTime() != null) {
            validateTimeRange(request.getStartTime(), request.getEndTime());
            entity.changeTimeRange(request.getStartTime(), request.getEndTime());
        }
        if (request.getPrice() != null) {
            entity.changePrice(request.getPrice());
        }
        if (request.getNote() != null) {
            entity.changeNote(request.getNote());
        }
        // 定員変更。null = 据え置き（部分更新）。指定時は 1 以上へ正規化し、予約数との関係で満席/空きを再評価する。
        if (request.getCapacity() != null) {
            entity.changeCapacity(normalizeCapacity(request.getCapacity()));
        }
        // 承認モード上書き:
        //   clearApprovalMode=true → null（チーム既定に従う）へ戻す
        //   approvalMode 指定あり   → その値で上書き
        //   いずれも無し            → 据え置き（部分更新）
        if (Boolean.TRUE.equals(request.getClearApprovalMode())) {
            entity.clearApprovalMode();
        } else if (request.getApprovalMode() != null) {
            entity.changeApprovalMode(request.getApprovalMode());
        }

        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロット更新: teamId={}, slotId={}", teamId, slotId);
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを論理削除する。
     *
     * <p>active な予約（PENDING / CONFIRMED）が紐づくスロットの削除は、
     * 予約をオーファン化させ以後キャンセル不能にする重大なデータ整合性バグを招くため拒否する（409）。
     * 予約入り枠を消したい場合は、先に予約を CANCELLED 等の終端状態へ遷移させること。</p>
     *
     * @param teamId チームID
     * @param slotId スロットID
     * @throws BusinessException スロット未存在（SLOT_NOT_FOUND）/ active 予約あり（SLOT_HAS_ACTIVE_RESERVATIONS）
     */
    @Transactional
    public void deleteSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);

        if (reservationRepository.existsByReservationSlotIdAndStatusIn(slotId, ACTIVE_RESERVATION_STATUSES)) {
            throw new BusinessException(ReservationErrorCode.SLOT_HAS_ACTIVE_RESERVATIONS);
        }

        entity.softDelete();
        slotRepository.save(entity);
        log.info("予約スロット削除: teamId={}, slotId={}", teamId, slotId);
    }

    /**
     * スロットをクローズする。
     *
     * @param teamId  チームID
     * @param slotId  スロットID
     * @param request クローズリクエスト
     * @return 更新されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse closeSlot(Long teamId, Long slotId, CloseSlotRequest request) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
        entity.close(request.getReason());
        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロットクローズ: teamId={}, slotId={}, reason={}", teamId, slotId, request.getReason());
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを再開する。
     *
     * @param teamId チームID
     * @param slotId スロットID
     * @return 更新されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse reopenSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
        entity.markAvailable();
        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロット再開: teamId={}, slotId={}", teamId, slotId);
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * 担当者のスロット一覧を取得する。
     *
     * @param staffUserId 担当者ユーザーID
     * @param from        開始日
     * @param to          終了日
     * @return スロットレスポンスリスト
     */
    public List<ReservationSlotResponse> listSlotsByStaff(Long staffUserId, LocalDate from, LocalDate to) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByStaffUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(staffUserId, from, to);
        return reservationMapper.toSlotResponseList(slots);
    }

    /**
     * スロットエンティティを取得する（内部利用）。
     *
     * @param slotId スロットID
     * @return スロットエンティティ
     */
    public ReservationSlotEntity getSlotEntity(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));
    }

    /**
     * スロットの予約数を +1 し、定員に達したら満席（FULL）にする。<b>オーバーブッキング防止の並行制御</b>。
     *
     * <p>設計書 F03.4 §3 に従い、条件付きアトミック UPDATE
     * （{@code WHERE slot_status='AVAILABLE' AND booked_count < capacity}）で満席超過を防ぐ。
     * 複数ユーザーが同一枠へ同時予約しても、確保できるのは定員数までで、超過分は <b>0 行更新</b>となり
     * {@link ReservationErrorCode#SLOT_FULL} を投げる。呼び出し元の予約作成トランザクション（予約 INSERT）ごと
     * ロールバックされるため、確保に失敗したユーザーの予約は残らない。</p>
     *
     * <p>以前はこのメソッドが名前に反して {@code markFull()} を呼ばず、かつ枠に定員も無かったため、
     * 同一枠へ無制限に予約できるオーバーブッキング事故が起きていた（実機E2Eで発見）。</p>
     *
     * @param entity スロットエンティティ（ID のみ使用）
     * @throws BusinessException 満席 or CLOSED で枠を確保できない場合（{@link ReservationErrorCode#SLOT_FULL}）
     */
    @Transactional
    public void incrementAndCheckFull(ReservationSlotEntity entity) {
        int updated = slotRepository.incrementBookedCountIfAvailable(entity.getId());
        if (updated == 0) {
            throw new BusinessException(ReservationErrorCode.SLOT_FULL);
        }
    }

    /**
     * スロットの予約数を -1 し、満席が解消されたら利用可能（AVAILABLE）に戻す（キャンセル時）。
     *
     * <p>アトミック UPDATE で {@code booked_count} を下限 0 でクランプしつつ減算し、
     * {@code FULL} だった枠が定員未満に戻れば {@code AVAILABLE} へ復帰させる（CLOSED は据え置き）。</p>
     *
     * @param entity スロットエンティティ（ID のみ使用）
     */
    @Transactional
    public void decrementAndReopen(ReservationSlotEntity entity) {
        slotRepository.decrementBookedCountAndReopen(entity.getId());
    }

    /**
     * 定員を 1 以上へ正規化する。{@code null}（未指定）は既定 1（＝1:1 指名）、1 未満は 1 に丸める。
     *
     * <p>DTO 側の {@code @Min(1)} で 0 以下は 400 になるが、Service 直接呼び出し（テスト等）や
     * 未指定時の防御として最終的にここでも 1 以上を保証し、builder への NULL 混入を防ぐ。</p>
     */
    private Integer normalizeCapacity(Integer capacity) {
        if (capacity == null || capacity < 1) {
            return 1;
        }
        return capacity;
    }

    /**
     * スロットを取得する。存在しない場合は例外をスローする。
     */
    private ReservationSlotEntity findSlotOrThrow(Long teamId, Long slotId) {
        return slotRepository.findByIdAndTeamId(slotId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));
    }

    /**
     * 時間範囲のバリデーション。createSlot / updateSlot / テンプレ CRUD から呼ばれる単一の検証点。
     *
     * <p>F03.4.2 で検証本体を {@link SlotTimeValidator} へ抽出し、週間テンプレート
     * （{@code ReservationSlotTemplateService}）と共有する（007/022 の再利用・別実装厳禁）。
     * 片方のみ指定（updateSlot で時刻据え置き等）の場合は検証をスキップする
     * （updateSlot 側で「両方非 null のときのみ」呼ぶ前提）。</p>
     */
    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        SlotTimeValidator.validateTimeRange(startTime, endTime);
    }

    /**
     * F03.4.2: lineId のチーム帰属検証（null = 共通枠で検証不要）。
     *
     * <p>他チーム/不存在（論理削除済み含む — {@code @SQLRestriction}）のラインは
     * 400（{@link ReservationErrorCode#LINE_NOT_FOUND}=001 再利用）。</p>
     */
    private void validateLineBelongsToTeam(Long teamId, Long lineId) {
        if (lineId == null) {
            return;
        }
        lineRepository.findByIdAndTeamId(lineId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.LINE_NOT_FOUND));
    }

    /**
     * F03.4.2: レスポンスへライン名を一括解決して後付けする（F-11 の {@code lineName}）。
     *
     * <p>ライン軸枠（lineId 非 null）が 1 件もなければ何もしない（既存挙動と追加クエリゼロで互換）。</p>
     */
    private List<ReservationSlotResponse> enrichLineNames(List<ReservationSlotResponse> responses) {
        List<Long> lineIds = responses.stream()
                .map(ReservationSlotResponse::getLineId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (lineIds.isEmpty()) {
            return responses;
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        lineRepository.findAllById(lineIds)
                .forEach(line -> names.put(line.getId(), line.getName()));
        return responses.stream()
                .map(response -> response.getLineId() != null
                        ? response.toBuilder().lineName(names.get(response.getLineId())).build()
                        : response)
                .toList();
    }
}
