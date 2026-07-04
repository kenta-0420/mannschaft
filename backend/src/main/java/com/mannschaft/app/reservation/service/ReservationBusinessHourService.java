package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.BlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourEntry;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 予約営業時間サービス。営業時間・ブロック時間（予約不可枠）の管理を担当する。
 *
 * <p>機能B（§3.B/§4.B/§5.B）: 予約不可枠に対象軸（TEAM/STAFF）を追加し、登録/更新時の 409 ガード
 * （overlap する active 予約が存在すると {@code RESERVATION_027}）と登録前 impact プレビューを提供する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationBusinessHourService {

    /** 409 ガード / impact で「予約が生きている」と見なす active ステータス（PENDING / CONFIRMED）。 */
    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final ReservationBusinessHourRepository businessHourRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final NameResolverService nameResolverService;
    private final ReservationMapper reservationMapper;

    /**
     * チームの営業時間設定を取得する。
     *
     * @param teamId チームID
     * @return 営業時間レスポンスリスト
     */
    public List<BusinessHourResponse> getBusinessHours(Long teamId) {
        List<ReservationBusinessHourEntity> hours = businessHourRepository.findByTeamIdOrderByIdAsc(teamId);
        return reservationMapper.toBusinessHourResponseList(hours);
    }

    /**
     * チームの営業時間設定を一括更新する。
     *
     * @param teamId  チームID
     * @param request 更新リクエスト
     * @return 更新された営業時間レスポンスリスト
     */
    @Transactional
    public List<BusinessHourResponse> updateBusinessHours(Long teamId, BusinessHoursUpdateRequest request) {
        List<ReservationBusinessHourEntity> result = new ArrayList<>();

        for (BusinessHourEntry entry : request.getHours()) {
            if (entry.getIsOpen() && entry.getOpenTime() != null && entry.getCloseTime() != null
                    && !entry.getOpenTime().isBefore(entry.getCloseTime())) {
                throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
            }

            ReservationBusinessHourEntity entity = businessHourRepository
                    .findByTeamIdAndDayOfWeek(teamId, entry.getDayOfWeek())
                    .map(existing -> {
                        existing.updateHours(entry.getIsOpen(), entry.getOpenTime(), entry.getCloseTime());
                        return existing;
                    })
                    .orElseGet(() -> ReservationBusinessHourEntity.builder()
                            .teamId(teamId)
                            .dayOfWeek(entry.getDayOfWeek())
                            .isOpen(entry.getIsOpen())
                            .openTime(entry.getOpenTime())
                            .closeTime(entry.getCloseTime())
                            .build());

            result.add(businessHourRepository.save(entity));
        }

        log.info("営業時間更新: teamId={}, entries={}", teamId, request.getHours().size());
        return reservationMapper.toBusinessHourResponseList(result);
    }

    /**
     * チームの特定日のブロック時間を取得する。
     *
     * @param teamId チームID
     * @param date   対象日
     * @return ブロック時間レスポンスリスト
     */
    public List<BlockedTimeResponse> getBlockedTimes(Long teamId, LocalDate date) {
        List<ReservationBlockedTimeEntity> blockedTimes =
                blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(teamId, date);
        return enrichWithResourceNames(blockedTimes);
    }

    /**
     * チームのブロック時間を日付範囲で取得する。
     *
     * @param teamId チームID
     * @param from   開始日
     * @param to     終了日
     * @return ブロック時間レスポンスリスト
     */
    public List<BlockedTimeResponse> listBlockedTimes(Long teamId, LocalDate from, LocalDate to) {
        List<ReservationBlockedTimeEntity> blockedTimes =
                blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(teamId, from, to);
        return enrichWithResourceNames(blockedTimes);
    }

    /**
     * ブロック時間（予約不可枠）を作成する。
     *
     * <p>機能B: {@code resourceType} を正規化（null→TEAM）し、overlap する active 予約が存在すれば
     * {@code RESERVATION_027}（409）で拒否する。overlap 0 件のときのみ {@code reservation_blocked_times}
     * に INSERT する（{@code reservation_slots} は触らない）。</p>
     *
     * @param teamId    チームID
     * @param request   作成リクエスト
     * @param createdBy 作成者ユーザーID
     * @return 作成されたブロック時間レスポンス
     */
    @Transactional
    public BlockedTimeResponse createBlockedTime(Long teamId, BlockedTimeRequest request, Long createdBy) {
        validateTimeRange(request.getStartTime(), request.getEndTime());
        ReservationBlockedResourceType resourceType = resolveResourceType(request.getResourceType());
        Long resourceId = resolveResourceId(resourceType, request.getResourceId());

        // 409 ガード: overlap する active 予約が 1 件以上なら拒否（副作用ゼロ）。
        guardNoActiveOverlap(teamId, request.getBlockedDate(), resourceType, resourceId,
                request.getStartTime(), request.getEndTime());

        ReservationBlockedTimeEntity entity = ReservationBlockedTimeEntity.builder()
                .teamId(teamId)
                .blockedDate(request.getBlockedDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .resourceType(resourceType)
                .resourceId(resourceId)
                .createdBy(createdBy)
                .build();

        ReservationBlockedTimeEntity saved = blockedTimeRepository.save(entity);
        log.info("予約不可枠作成: teamId={}, date={}, resourceType={}, resourceId={}",
                teamId, request.getBlockedDate(), resourceType, resourceId);
        return enrichWithResourceName(saved);
    }

    /**
     * ブロック時間（予約不可枠）を更新する。
     *
     * @param teamId    チームID
     * @param blockedId ブロック時間ID
     * @param request   更新リクエスト
     * @return 更新されたブロック時間レスポンス
     */
    @Transactional
    public BlockedTimeResponse updateBlockedTime(Long teamId, Long blockedId, BlockedTimeRequest request) {
        ReservationBlockedTimeEntity entity = blockedTimeRepository.findByIdAndTeamId(blockedId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.BLOCKED_TIME_NOT_FOUND));

        validateTimeRange(request.getStartTime(), request.getEndTime());
        ReservationBlockedResourceType resourceType = resolveResourceType(request.getResourceType());
        Long resourceId = resolveResourceId(resourceType, request.getResourceId());

        // 更新後の枠に対しても 409 ガードを適用する（新しい対象軸/時間帯で overlap する active 予約を弾く）。
        guardNoActiveOverlap(teamId, request.getBlockedDate(), resourceType, resourceId,
                request.getStartTime(), request.getEndTime());

        entity.update(request.getBlockedDate(), request.getStartTime(), request.getEndTime(),
                request.getReason(), resourceType, resourceId);
        ReservationBlockedTimeEntity saved = blockedTimeRepository.save(entity);
        log.info("予約不可枠更新: teamId={}, blockedId={}, resourceType={}, resourceId={}",
                teamId, blockedId, resourceType, resourceId);
        return enrichWithResourceName(saved);
    }

    /**
     * ブロック時間を削除する。
     *
     * <p>予約不可枠は slot を CLOSED に永続化しないため、削除は {@code reservation_blocked_times}
     * 行の削除のみで {@code reservation_slots} は不変。overlap が消えれば以後の runtime 判定で
     * 自動的に予約可能に戻る（§5.B）。</p>
     *
     * @param teamId    チームID
     * @param blockedId ブロック時間ID
     */
    @Transactional
    public void deleteBlockedTime(Long teamId, Long blockedId) {
        ReservationBlockedTimeEntity entity = blockedTimeRepository.findByIdAndTeamId(blockedId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.BLOCKED_TIME_NOT_FOUND));
        blockedTimeRepository.delete(entity);
        log.info("予約不可枠削除: teamId={}, blockedId={}", teamId, blockedId);
    }

    /**
     * 予約不可枠 登録前の影響プレビュー（機能B・§4.B）。overlap する既存 active 予約の
     * 件数＋一覧（管理用・氏名込み）を返す。<b>副作用ゼロ</b>。
     *
     * @param teamId       チームID
     * @param date         予約不可にしたい日
     * @param resourceType 対象軸（null→TEAM 正規化）
     * @param resourceId   STAFF 軸のときの対象スタッフ user_id
     * @param startTime    部分ブロックの開始（省略＝全日）
     * @param endTime      部分ブロックの終了（省略＝全日）
     * @return 影響プレビュー
     */
    public BlockedTimeImpactResponse getBlockedTimeImpact(
            Long teamId, LocalDate date, ReservationBlockedResourceType resourceType, Long resourceId,
            LocalTime startTime, LocalTime endTime) {

        validateTimeRange(startTime, endTime);
        ReservationBlockedResourceType type = resolveResourceType(resourceType);
        Long resolvedResourceId = resolveResourceId(type, resourceId);

        List<ReservationEntity> overlapping =
                findActiveOverlappingReservations(teamId, date, type, resolvedResourceId, startTime, endTime);

        // 枠情報（担当スタッフ）を一括取得（N+1 回避）。
        Set<Long> slotIds = overlapping.stream()
                .map(ReservationEntity::getReservationSlotId)
                .collect(Collectors.toSet());
        Map<Long, ReservationSlotEntity> slots = slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        // 予約者氏名・担当スタッフ氏名を一括解決（N+1 回避）。
        Set<Long> userIds = overlapping.stream().map(ReservationEntity::getUserId).collect(Collectors.toSet());
        Set<Long> staffIds = slots.values().stream()
                .map(ReservationSlotEntity::getStaffUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> userNames = nameResolverService.resolveUserFullNames(userIds);
        Map<Long, String> staffNames = nameResolverService.resolveUserFullNames(staffIds);

        List<BlockedTimeImpactResponse.ImpactedReservationDto> dtos = overlapping.stream()
                .map(r -> {
                    ReservationSlotEntity slot = slots.get(r.getReservationSlotId());
                    Long staffUserId = slot != null ? slot.getStaffUserId() : null;
                    return new BlockedTimeImpactResponse.ImpactedReservationDto(
                            r.getId(),
                            r.getUserId(),
                            userNames.get(r.getUserId()),
                            r.getReservationSlotId(),
                            staffUserId != null ? staffNames.get(staffUserId) : null,
                            slot != null ? slot.getStartTime() : null,
                            slot != null ? slot.getEndTime() : null,
                            r.getStatus() != null ? r.getStatus().name() : null);
                })
                .toList();

        return BlockedTimeImpactResponse.builder()
                .affectedCount(dtos.size())
                .reservations(dtos)
                .build();
    }

    /**
     * チームの営業時間設定が存在するか確認する。
     *
     * @param teamId チームID
     * @return 設定が存在する場合 true
     */
    public boolean hasBusinessHours(Long teamId) {
        return businessHourRepository.existsByTeamId(teamId);
    }

    // ========================================
    // 機能B ヘルパー
    // ========================================

    /**
     * 開始・終了時刻のバリデーション。両方 NULL（全日）または両方指定（部分）のみ許可。
     * 片方だけ指定・開始 &ge; 終了は 400（{@link ReservationErrorCode#INVALID_TIME_RANGE}）。
     */
    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        if ((startTime == null) != (endTime == null)) {
            // 片方だけ NULL は不可（§3「両方 NULL or 両方指定」）。
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
        }
        if (startTime != null && !startTime.isBefore(endTime)) {
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
        }
    }

    /** {@code resourceType} を正規化する（未指定＝null → TEAM）。 */
    private ReservationBlockedResourceType resolveResourceType(ReservationBlockedResourceType raw) {
        return raw != null ? raw : ReservationBlockedResourceType.TEAM;
    }

    /**
     * {@code resourceId} を正規化する。STAFF 軸は必須（未指定は 400）。それ以外（TEAM 等）は NULL。
     */
    private Long resolveResourceId(ReservationBlockedResourceType type, Long resourceId) {
        if (type == ReservationBlockedResourceType.STAFF) {
            if (resourceId == null) {
                // resourceType='STAFF' かつ resourceId 未指定は入力不正（400）。
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
            return resourceId;
        }
        return null;
    }

    /**
     * 提案枠と overlap する active 予約が 1 件以上あれば {@code RESERVATION_027}（409）で拒否する。
     */
    private void guardNoActiveOverlap(Long teamId, LocalDate date, ReservationBlockedResourceType type,
                                      Long resourceId, LocalTime startTime, LocalTime endTime) {
        List<ReservationEntity> overlapping =
                findActiveOverlappingReservations(teamId, date, type, resourceId, startTime, endTime);
        if (!overlapping.isEmpty()) {
            throw new BusinessException(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);
        }
    }

    /**
     * 提案された予約不可枠と overlap する active 予約を取得する（409 ガード・impact 共通の観測点）。
     * 全日（start/end 両 NULL）は {@code [LocalTime.MIN, LocalTime.MAX]} に展開して「その日の全 slot」に一致させる。
     */
    private List<ReservationEntity> findActiveOverlappingReservations(
            Long teamId, LocalDate date, ReservationBlockedResourceType type, Long resourceId,
            LocalTime startTime, LocalTime endTime) {
        // TEAM 軸は全 slot 対象（resourceId=null）、STAFF 軸は resourceId で絞る。
        Long queryResourceId = (type == ReservationBlockedResourceType.STAFF) ? resourceId : null;
        LocalTime effectiveStart = startTime != null ? startTime : LocalTime.MIN;
        LocalTime effectiveEnd = endTime != null ? endTime : LocalTime.MAX;
        return reservationRepository.findActiveReservationsOverlappingUnavailability(
                teamId, date, queryResourceId, effectiveStart, effectiveEnd, ACTIVE_STATUSES);
    }

    /** 単一の予約不可枠を STAFF 時の担当スタッフ表示名（resourceName）付きで変換する。 */
    private BlockedTimeResponse enrichWithResourceName(ReservationBlockedTimeEntity entity) {
        return enrichWithResourceNames(List.of(entity)).get(0);
    }

    /**
     * 予約不可枠リストを、STAFF 軸の担当スタッフ表示名（resourceName）を一括解決して付与しつつ変換する
     * （N+1 回避）。TEAM 軸は resourceName=null のまま。
     */
    private List<BlockedTimeResponse> enrichWithResourceNames(List<ReservationBlockedTimeEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Set<Long> staffIds = entities.stream()
                .filter(e -> e.getResourceType() == ReservationBlockedResourceType.STAFF)
                .map(ReservationBlockedTimeEntity::getResourceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> staffNames = staffIds.isEmpty()
                ? Collections.emptyMap()
                : nameResolverService.resolveUserFullNames(staffIds);

        return entities.stream()
                .map(e -> {
                    BlockedTimeResponse base = reservationMapper.toBlockedTimeResponse(e);
                    String resourceName = e.getResourceType() == ReservationBlockedResourceType.STAFF
                            ? staffNames.get(e.getResourceId())
                            : null;
                    return base.toBuilder()
                            .resource(new BlockedTimeResponse.ResourceDto(
                                    e.getResourceType() != null ? e.getResourceType().name() : null,
                                    e.getResourceId(),
                                    resourceName))
                            .build();
                })
                .toList();
    }
}
