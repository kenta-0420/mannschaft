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
     * <p><b>認可（BOLA 封鎖）</b>: path の {@code teamId} を鵜呑みにせず、先に連絡 entity を fetch し
     * <b>entity 由来の {@code teamId}</b> と突合する。不一致なら存在秘匿のため 404 を返す。
     * そのうえで entity 由来スコープの ADMIN/DEPUTY_ADMIN（＝教員相当・後述）のみ許可する。
     * 同ドメイン {@code TransitionAlertService#resolveAlert} と同型。</p>
     *
     * <p><b>「担任」のロール写像</b>: 設計書 F03.13 §5.3 は本 EP の権限を「担任」と定めるが、
     * ロール体系に {@code TEACHER} は存在せず担任判定 API も無い
     * （{@code ClassHomeroomRepository} に {@code homeroomTeacherUserId} 検索は無い）。
     * マスター裁可 A-1（{@code docs/security/03_role_authority_model.md} §7.1・2026-05-30）に従い
     * 「学校チーム（クラス）の ADMIN/DEPUTY_ADMIN ＝教員相当」として per-scope 認可する。</p>
     *
     * <p><b>保護者経路を設けない理由</b>: 本 EP は担任が連絡を受理する教職員側の操作であり、
     * 保護者は送信（{@link #submitNotice}）と自分の履歴（{@link #getMyNotices}）で完結する。
     * PR #2242 の二経路（教職員＋保護者）は「生徒個人のデータを保護者も閲覧する」read に適用される型で、
     * 本 EP には該当しない。</p>
     *
     * @param teamId             path のクラスチームID（entity 由来 teamId との突合に使用）
     * @param noticeId           連絡 ID
     * @param acknowledgerUserId 担任のユーザーID
     * @return 更新後の連絡レスポンス
     */
    public FamilyAttendanceNoticeResponse acknowledgeNotice(Long teamId, Long noticeId, Long acknowledgerUserId) {
        FamilyAttendanceNoticeEntity entity = findNoticeInTeamOrHide(teamId, noticeId);
        // 認可: entity 由来 scope（= path と一致確認済みの teamId）の ADMIN／DEPUTY_ADMIN のみ。
        accessControlService.checkAdminOrAbove(acknowledgerUserId, entity.getTeamId(), "TEAM");

        // toBuilder().build() で作り直すと BaseEntity.id が引き継がれず INSERT 化する（行重複）。
        // managed entity を直接ミューテートし JPA dirty checking で UPDATE する。
        entity.acknowledge(acknowledgerUserId, LocalDateTime.now());
        noticeRepository.save(entity);
        notificationService.notifyFamilyNoticeAcknowledged(entity);
        return buildResponse(entity);
    }

    /**
     * 担任が保護者連絡を出欠レコードに反映する。
     *
     * <p>認可は {@link #acknowledgeNotice} と同一（entity 由来 teamId 突合 → 404 秘匿 →
     * 当該チームの ADMIN/DEPUTY_ADMIN＝教員相当のみ許可）。</p>
     *
     * @param teamId          path のクラスチームID（entity 由来 teamId との突合に使用）
     * @param noticeId        連絡 ID
     * @param operatorUserId  操作者（担任）のユーザーID
     * @return 更新後の連絡レスポンス
     */
    public FamilyAttendanceNoticeResponse applyToAttendanceRecord(Long teamId, Long noticeId, Long operatorUserId) {
        FamilyAttendanceNoticeEntity entity = findNoticeInTeamOrHide(teamId, noticeId);
        // 認可: entity 由来 scope（= path と一致確認済みの teamId）の ADMIN／DEPUTY_ADMIN のみ。
        accessControlService.checkAdminOrAbove(operatorUserId, entity.getTeamId(), "TEAM");

        if (Boolean.TRUE.equals(entity.getAppliedToRecord())) {
            throw new BusinessException(SchoolErrorCode.FAMILY_NOTICE_ALREADY_APPLIED);
        }

        // toBuilder().build() で作り直すと BaseEntity.id が引き継がれず INSERT 化する（行重複）。
        // managed entity を直接ミューテートし JPA dirty checking で UPDATE する。
        entity.markAppliedToRecord();
        noticeRepository.save(entity);
        return buildResponse(entity);
    }

    // ========================================
    // 担任: 一覧取得
    // ========================================

    /**
     * 担任が当日の保護者連絡一覧を取得する。
     *
     * <p><b>認可</b>: クラス全児童の連絡（欠席理由・体調・添付）を横断で返す大量 PII 参照のため、
     * 対象チームの ADMIN/DEPUTY_ADMIN（＝教員相当。マスター裁可 A-1・
     * {@code docs/security/03_role_authority_model.md} §7.1）のみに限定する。
     * スコープは URL パスで明示宣言されており entity 側に別スコープは存在しないため、
     * 宣言スコープをそのまま認可対象とする（越境は当該チームの権限が無い＝403 に収束する）。</p>
     *
     * @param teamId        クラスチームID
     * @param date          対象日
     * @param actorUserId   操作者（担任）のユーザーID
     * @return 保護者連絡一覧レスポンス
     */
    @Transactional(readOnly = true)
    public FamilyNoticeListResponse getTeamNotices(Long teamId, LocalDate date, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");

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

    /**
     * 連絡を取得し、path の {@code teamId} 配下であることを検証する（BOLA 封鎖・存在秘匿）。
     *
     * <p>不在・別チーム所属のいずれも {@code FAMILY_NOTICE_NOT_FOUND}（404）に収束させ、
     * 他クラスの連絡が「存在するかどうか」を非権限者に開示しない。</p>
     *
     * @param teamId   path のクラスチームID
     * @param noticeId 連絡 ID
     * @return path の teamId に属することを確認済みの連絡 entity
     */
    private FamilyAttendanceNoticeEntity findNoticeInTeamOrHide(Long teamId, Long noticeId) {
        FamilyAttendanceNoticeEntity entity = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND));
        if (teamId == null || !teamId.equals(entity.getTeamId())) {
            throw new BusinessException(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND);
        }
        return entity;
    }

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
