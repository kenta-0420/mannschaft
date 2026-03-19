package com.mannschaft.app.membership;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.dto.CheckinLocationResponse;
import com.mannschaft.app.membership.dto.CreateCheckinLocationRequest;
import com.mannschaft.app.membership.dto.DeleteLocationResponse;
import com.mannschaft.app.membership.dto.LocationQrResponse;
import com.mannschaft.app.membership.dto.UpdateCheckinLocationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * セルフチェックイン拠点サービス。拠点のCRUD・QRトークン発行を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckinLocationService {

    private static final int MAX_LOCATIONS_PER_SCOPE = 20;

    private final CheckinLocationRepository locationRepository;
    private final MemberCardCheckinRepository checkinRepository;
    private final QrTokenService qrTokenService;

    /**
     * 拠点一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 拠点一覧
     */
    public ApiResponse<List<CheckinLocationResponse>> getLocations(ScopeType scopeType, Long scopeId) {
        List<CheckinLocationEntity> locations = locationRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtAsc(scopeType, scopeId);

        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);

        List<CheckinLocationResponse> responses = locations.stream()
                .map(loc -> {
                    long todayCount = checkinRepository.countByCheckinLocationIdAndCheckedInAtAfter(
                            loc.getId(), todayStart);
                    return new CheckinLocationResponse(
                            loc.getId(), loc.getName(), loc.getLocationCode(),
                            loc.getIsActive(), loc.getAutoCompleteReservation(),
                            todayCount, loc.getCreatedAt());
                })
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * 拠点を作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param request   作成リクエスト
     * @param userId    作成者ユーザーID
     * @return 作成された拠点
     */
    @Transactional
    public ApiResponse<CheckinLocationResponse> createLocation(
            ScopeType scopeType, Long scopeId,
            CreateCheckinLocationRequest request, Long userId) {

        // 拠点数上限チェック
        long currentCount = locationRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
        if (currentCount >= MAX_LOCATIONS_PER_SCOPE) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_020);
        }

        CheckinLocationEntity location = CheckinLocationEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .locationCode(UUID.randomUUID().toString())
                .locationSecret(qrTokenService.generateSecret())
                .isActive(true)
                .autoCompleteReservation(
                        request.getAutoCompleteReservation() != null
                                ? request.getAutoCompleteReservation() : true)
                .createdBy(userId)
                .build();

        locationRepository.save(location);

        log.info("チェックイン拠点作成: locationId={}, scopeType={}, scopeId={}",
                location.getId(), scopeType, scopeId);

        CheckinLocationResponse response = new CheckinLocationResponse(
                location.getId(), location.getName(), location.getLocationCode(),
                location.getIsActive(), location.getAutoCompleteReservation(),
                0L, location.getCreatedAt());

        return ApiResponse.of(response);
    }

    /**
     * 拠点を更新する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param locationId 拠点ID
     * @param request    更新リクエスト
     * @return 更新された拠点
     */
    @Transactional
    public ApiResponse<CheckinLocationResponse> updateLocation(
            ScopeType scopeType, Long scopeId, Long locationId,
            UpdateCheckinLocationRequest request) {

        CheckinLocationEntity location = findLocationOrThrow(scopeType, scopeId, locationId);

        location.update(request.getName(), request.getIsActive(), request.getAutoCompleteReservation());
        locationRepository.save(location);

        log.info("チェックイン拠点更新: locationId={}", locationId);

        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        long todayCount = checkinRepository.countByCheckinLocationIdAndCheckedInAtAfter(
                location.getId(), todayStart);

        CheckinLocationResponse response = new CheckinLocationResponse(
                location.getId(), location.getName(), location.getLocationCode(),
                location.getIsActive(), location.getAutoCompleteReservation(),
                todayCount, location.getCreatedAt());

        return ApiResponse.of(response);
    }

    /**
     * 拠点を論理削除する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param locationId 拠点ID
     * @return 削除結果
     */
    @Transactional
    public ApiResponse<DeleteLocationResponse> deleteLocation(
            ScopeType scopeType, Long scopeId, Long locationId) {

        CheckinLocationEntity location = findLocationOrThrow(scopeType, scopeId, locationId);

        location.softDelete();
        locationRepository.save(location);

        log.info("チェックイン拠点削除: locationId={}", locationId);

        return ApiResponse.of(new DeleteLocationResponse(location.getId(), location.getDeletedAt()));
    }

    /**
     * 拠点QRコードの印刷用データを取得する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param locationId 拠点ID
     * @return QRトークン情報
     */
    public ApiResponse<LocationQrResponse> getLocationQr(
            ScopeType scopeType, Long scopeId, Long locationId) {

        CheckinLocationEntity location = findLocationOrThrow(scopeType, scopeId, locationId);

        String qrToken = qrTokenService.generateLocationQrToken(
                location.getLocationCode(), location.getLocationSecret());

        // TODO: スコープ名の実取得
        String scopeName = "TODO: スコープ名取得";

        LocationQrResponse response = new LocationQrResponse(
                location.getId(),
                location.getName(),
                qrToken,
                scopeName,
                "このQRコードを印刷し、入口に掲示してください。会員がスマートフォンで読み取ると自動でチェックインされます。"
        );

        return ApiResponse.of(response);
    }

    // ========== private methods ==========

    private CheckinLocationEntity findLocationOrThrow(ScopeType scopeType, Long scopeId, Long locationId) {
        return locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        locationId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(MembershipErrorCode.MEMBERSHIP_019));
    }
}
