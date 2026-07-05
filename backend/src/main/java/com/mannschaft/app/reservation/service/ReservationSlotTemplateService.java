package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.reservation.dto.CreateSlotTemplateRequest;
import com.mannschaft.app.reservation.dto.DeleteSlotTemplateResponse;
import com.mannschaft.app.reservation.dto.GenerateSlotsRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateListResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotTemplateRequest;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 週間テンプレート CRUD サービス（F03.4.2 §5.1）。
 *
 * <p>重複/上限検証・IDOR 秘匿（404=RESERVATION_036）・generate のレートリミット＋委譲・監査ログを担う。
 * 生成本体は {@link ReservationSlotGenerationService}（単一実装）へ委譲する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationSlotTemplateService {

    /** F03.4.2 §3.2: 1 チームあたりのテンプレ行数上限。 */
    static final long MAX_TEMPLATES_PER_TEAM = 500L;

    private final ReservationSlotTemplateRepository templateRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationSlotGenerationService generationService;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;
    private final AuditLogService auditLogService;
    private final ValkeyRateLimiter rateLimiter;

    /** テンプレ一覧（曜日→開始時刻順）＋メタ（totalTemplates/limit）。 */
    public SlotTemplateListResponse listTemplates(Long teamId) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }

    /** テンプレ作成（上限500・時刻007/022・ライン001・重複帯検証）。 */
    @Transactional
    public SlotTemplateResponse createTemplate(Long teamId, CreateSlotTemplateRequest request, Long createdBy) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }

    /** テンプレ部分更新（null=据え置き・clearLineId・isActive 切替。既生成枠へ遡及しない）。 */
    @Transactional
    public SlotTemplateResponse updateTemplate(Long teamId, UUID templateId, UpdateSlotTemplateRequest request,
                                               Long updatedBy) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }

    /** テンプレ物理削除（生成済み枠は FK SET NULL で残置・orphanedSlotCount を返す）。 */
    @Transactional
    public DeleteSlotTemplateResponse deleteTemplate(Long teamId, UUID templateId, Long deletedBy) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }

    /** 一括生成（レートリミット 2回/分/チーム → 429=RESERVATION_044。生成本体へ委譲・監査ログ）。 */
    public GenerateSlotsResponse generate(Long teamId, GenerateSlotsRequest request, Long userId) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }
}
