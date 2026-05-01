package com.mannschaft.app.school.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeRequest;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeResponse;
import com.mannschaft.app.school.dto.FamilyNoticeListResponse;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.FamilyAttendanceNoticeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 保護者連絡サービス。
 *
 * <p>保護者による欠席・遅刻連絡の送信、担任による確認・出欠反映、一覧取得を担当する。
 * reasonDetail は {@link com.mannschaft.app.common.EncryptedStringConverter} で透過的に暗号化される。
 * 添付ファイルは R2 オブジェクトキーとして保存し、取得時に Pre-signed URL を生成する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class FamilyAttendanceNoticeService {

    private static final Duration DOWNLOAD_URL_TTL = Duration.ofHours(1);

    private final FamilyAttendanceNoticeRepository noticeRepository;
    private final UserCareLinkRepository userCareLinkRepository;
    private final AccessControlService accessControlService;
    private final StorageService storageService;
    private final SchoolAttendanceNotificationService notificationService;
    private final ObjectMapper objectMapper;

    // ========================================
    // 保護者: 連絡送信
    // ========================================

    /**
     * 保護者が欠席・遅刻連絡を送信する。
     *
     * <p>送信者が対象生徒への ACTIVE なケアリンクを持つか検証してから保存する。</p>
     *
     * @param submitterUserId 送信者（保護者）のユーザーID
     * @param req             連絡送信リクエスト
     * @return 保存された連絡レスポンス
     */
    public FamilyAttendanceNoticeResponse submitNotice(Long submitterUserId, FamilyAttendanceNoticeRequest req) {
        accessControlService.checkCareLink(submitterUserId, req.getStudentUserId());

        FamilyAttendanceNoticeEntity entity = FamilyAttendanceNoticeEntity.builder()
                .teamId(req.getTeamId())
                .studentUserId(req.getStudentUserId())
                .submitterUserId(submitterUserId)
                .attendanceDate(req.getAttendanceDate())
                .noticeType(req.getNoticeType())
                .reason(req.getReason())
                .reasonDetail(req.getReasonDetail())
                .expectedArrivalTime(parseTime(req.getExpectedArrivalTime()))
                .expectedLeaveTime(parseTime(req.getExpectedLeaveTime()))
                .attachedFileKeys(serializeFileKeys(req.getAttachedFileKeys()))
                .appliedToRecord(false)
                .build();

        entity = noticeRepository.save(entity);
        notificationService.notifyFamilyNoticeSubmitted(entity);
        return buildResponse(entity);
    }

    // ========================================
    // 担任: 確認・反映
    // ========================================

    /**
     * 担任が保護者連絡を確認済みにする。
     *
     * @param noticeId          連絡 ID
     * @param acknowledgerUserId 担任のユーザーID
     * @return 更新後の連絡レスポンス
     */
    public FamilyAttendanceNoticeResponse acknowledgeNotice(Long noticeId, Long acknowledgerUserId) {
        FamilyAttendanceNoticeEntity entity = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND));

        entity = entity.toBuilder()
                .acknowledgedBy(acknowledgerUserId)
                .acknowledgedAt(LocalDateTime.now())
                .build();

        entity = noticeRepository.save(entity);
        notificationService.notifyFamilyNoticeAcknowledged(entity);
        return buildResponse(entity);
    }

    /**
     * 担任が保護者連絡を出欠レコードに反映する。
     *
     * @param noticeId        連絡 ID
     * @param operatorUserId  操作者（担任）のユーザーID
     * @return 更新後の連絡レスポンス
     */
    public FamilyAttendanceNoticeResponse applyToAttendanceRecord(Long noticeId, Long operatorUserId) {
        FamilyAttendanceNoticeEntity entity = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND));

        if (Boolean.TRUE.equals(entity.getAppliedToRecord())) {
            throw new BusinessException(SchoolErrorCode.FAMILY_NOTICE_ALREADY_APPLIED);
        }

        entity = entity.toBuilder()
                .appliedToRecord(true)
                .build();

        return buildResponse(noticeRepository.save(entity));
    }

    // ========================================
    // 担任: 一覧取得
    // ========================================

    /**
     * 担任が当日の保護者連絡一覧を取得する。
     *
     * @param teamId クラスチームID
     * @param date   対象日
     * @return 保護者連絡一覧レスポンス
     */
    @Transactional(readOnly = true)
    public FamilyNoticeListResponse getTeamNotices(Long teamId, LocalDate date) {
        List<FamilyAttendanceNoticeEntity> records =
                noticeRepository.findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(teamId, date);

        List<FamilyAttendanceNoticeResponse> responses = records.stream()
                .map(this::buildResponse)
                .toList();

        int unacknowledgedCount = (int) records.stream()
                .filter(e -> e.getAcknowledgedBy() == null)
                .count();

        return FamilyNoticeListResponse.builder()
                .teamId(teamId)
                .attendanceDate(date)
                .records(responses)
                .totalCount(records.size())
                .unacknowledgedCount(unacknowledgedCount)
                .build();
    }

    // ========================================
    // 保護者: 送信履歴
    // ========================================

    /**
     * 保護者が自分の送信履歴を取得する。
     *
     * @param submitterUserId 送信者（保護者）のユーザーID
     * @param from            開始日
     * @param to              終了日
     * @return 連絡一覧
     */
    @Transactional(readOnly = true)
    public List<FamilyAttendanceNoticeResponse> getMyNotices(Long submitterUserId, LocalDate from, LocalDate to) {
        return noticeRepository
                .findBySubmitterUserIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(submitterUserId, from, to)
                .stream()
                .map(this::buildResponse)
                .toList();
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    private FamilyAttendanceNoticeResponse buildResponse(FamilyAttendanceNoticeEntity entity) {
        List<String> downloadUrls = buildDownloadUrls(entity.getAttachedFileKeys());
        return FamilyAttendanceNoticeResponse.from(entity, downloadUrls);
    }

    private List<String> buildDownloadUrls(String fileKeysJson) {
        if (fileKeysJson == null || fileKeysJson.isBlank()) {
            return List.of();
        }
        List<String> keys = deserializeFileKeys(fileKeysJson);
        return keys.stream()
                .map(key -> storageService.generateDownloadUrl(key, DOWNLOAD_URL_TTL))
                .toList();
    }

    private String serializeFileKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException e) {
            log.warn("添付ファイルキーのシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }

    private List<String> deserializeFileKeys(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("添付ファイルキーのデシリアライズに失敗: {}", e.getMessage());
            return List.of();
        }
    }

    private LocalTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return null;
        }
        return LocalTime.parse(timeStr);
    }
}
