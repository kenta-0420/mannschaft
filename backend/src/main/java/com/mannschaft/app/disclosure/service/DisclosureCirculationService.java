package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.RecipientEntry;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartRequest;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartResponse;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 重要事項説明書 電子印鑑承認回覧連携サービス（F09.14 Phase 3-D）。
 *
 * <p>設計書 §4 / §5.6 に対応する。出力済の {@code disclosure_exports} 行に対して、
 * F05.2 {@link CirculationService} を直接呼び出して回覧文書を作成し、
 * {@code circulation_document_id} を保存する。</p>
 *
 * <p><b>ドメイン境界</b>: F05.2（回覧）と F09.14（重説書）の二ドメインに跨る @Transactional
 * となるが、現状は F05.2 にイベント発行が無いためモノリス前提で直接呼び出している。
 * クロスドメイン FK は V61.017 で撤去済みで、参照整合性は本サービスとクリーンアップハンドラ
 * （{@link DisclosureCirculationCleanupHandler}）で保証する。
 * 将来 F05.2 が {@code CirculationDocumentCreatedEvent} 等を発行できるようになったら
 * イベント駆動化を検討する（CLAUDE.md「ドメイン境界の原則」§5）。</p>
 *
 * <p><b>改ざん検出強化（§6.3）</b>: 既存 {@code output_sha256} 検証に加え、F05.3
 * {@code seal_stamp_logs} の証跡ログ参照は本 PR では未実装（TODO コメント参照）。
 * 押印フローが F05.3 経由で実装されたタイミングで、押印時の SHA-256 とリンクした
 * 監査クエリを追加する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureCirculationService {

    /** 設計書 §3 で許容されるスコープ種別。 */
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final DisclosureExportRepository exportRepository;

    /**
     * F05.2 回覧サービス。
     * <p>TODO: 将来イベント駆動化候補（F05.2 が {@code CirculationDocumentCreatedEvent} を
     * 発行できるようになったら、本サービスは {@code applicationEventPublisher} 経由に切替予定）。</p>
     */
    private final CirculationService circulationService;

    /** 認可根治戦役 Wave3-B4: 回覧開始は ADMIN/DEPUTY_ADMIN 以上のみ許可する。 */
    private final AccessControlService accessControlService;

    /**
     * 出力履歴に対して電子印鑑承認回覧を開始する。
     *
     * <p>処理:
     * <ol>
     *   <li>出力履歴を取得し、組織スコープを照合（IDOR ガード, DISCLOSURE_002）</li>
     *   <li>既に {@code circulation_document_id} が設定されている場合は二重起動として 409（DISCLOSURE_003）</li>
     *   <li>F05.2 {@link CirculationService#createDocument} を呼び出して回覧文書を作成</li>
     *   <li>{@link CirculationService#activateDocument} で ACTIVE 化</li>
     *   <li>{@code disclosure_exports.circulation_document_id} に保存（{@link DisclosureExportEntity#linkCirculationDocument}）</li>
     * </ol>
     * </p>
     *
     * @param scopeId  組織 ID
     * @param exportId 出力履歴 ID
     * @param userId   操作者ユーザー ID（回覧文書の作成者となる）
     * @param request  回覧開始リクエスト
     * @return 回覧開始レスポンス
     */
    @Transactional
    public DisclosureCirculationStartResponse startCirculation(
            Long scopeId, Long exportId, Long userId, DisclosureCirculationStartRequest request) {

        if (request == null
                || request.recipientUserIds() == null
                || request.recipientUserIds().isEmpty()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        // 1. 出力履歴取得 + IDOR チェック
        DisclosureExportEntity exportEntity = exportRepository
                .findByIdAndDeletedAtIsNull(exportId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
        ensureScope(exportEntity.getScopeType(), exportEntity.getScopeId(), scopeId);
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);

        // 2. 二重起動防止
        if (exportEntity.getCirculationDocumentId() != null) {
            log.warn("回覧重複起動: exportId={}, 既存 circulationDocumentId={}",
                    exportId, exportEntity.getCirculationDocumentId());
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_003);
        }

        // 3. 回覧モード解析
        CirculationMode mode = parseCirculationMode(request.circulationMode());

        // 4. 受信者リスト構築（重複排除して順序を維持）
        List<RecipientEntry> recipients = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (Long uid : request.recipientUserIds()) {
            if (uid == null || seen.contains(uid)) {
                continue;
            }
            seen.add(uid);
            recipients.add(new RecipientEntry(uid, recipients.size()));
        }
        if (recipients.isEmpty()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        // 5. F05.2 回覧文書作成（直接 @Autowired で呼出。モノリス前提）
        CreateDocumentRequest createRequest = buildCreateRequest(
                exportEntity, mode, request.dueDate(), recipients);
        DocumentResponse created = circulationService.createDocument(
                SCOPE_ORGANIZATION, scopeId, userId, createRequest);

        // 6. 公開（ACTIVE 化）
        DocumentResponse activated = circulationService.activateDocument(
                SCOPE_ORGANIZATION, scopeId, created.getId());

        // 7. disclosure_exports.circulation_document_id を更新
        exportEntity.linkCirculationDocument(activated.getId());
        exportRepository.save(exportEntity);

        log.info("重説書回覧開始: exportId={}, circulationDocumentId={}, status={}, recipientCount={}",
                exportId, activated.getId(), activated.getStatus(), recipients.size());

        return new DisclosureCirculationStartResponse(
                exportEntity.getId(),
                activated.getId(),
                CirculationStatus.valueOf(activated.getStatus()));
    }

    /**
     * 出力履歴のスコープと URL の組織 ID を照合する。
     */
    private void ensureScope(String entityScopeType, Long entityScopeId, Long expectedScopeId) {
        if (!SCOPE_ORGANIZATION.equals(entityScopeType) || !entityScopeId.equals(expectedScopeId)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
        }
    }

    /**
     * 回覧モード文字列を {@link CirculationMode} に変換する。不正値は DISCLOSURE_004。
     */
    private CirculationMode parseCirculationMode(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        try {
            return CirculationMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004, e);
        }
    }

    /**
     * 出力履歴情報から F05.2 の {@link CreateDocumentRequest} を構築する。
     *
     * <p>件名は「重要事項説明書 承認回覧（exportId=...）」、本文は提出先メモを含む簡易テキスト。
     * stampDisplayStyle / priority / reminder はデフォルト値で作成される。</p>
     */
    private CreateDocumentRequest buildCreateRequest(
            DisclosureExportEntity exportEntity,
            CirculationMode mode,
            LocalDate dueDate,
            List<RecipientEntry> recipients) {
        String title = "重要事項説明書 承認回覧（exportId=" + exportEntity.getId() + "）";
        StringBuilder body = new StringBuilder();
        body.append("以下の重要事項説明書出力に対する承認回覧です。\n\n");
        body.append("- 出力履歴ID: ").append(exportEntity.getId()).append('\n');
        body.append("- 様式: ").append(exportEntity.getTemplateCodeSnapshot())
                .append(" / ").append(exportEntity.getTemplateVersionSnapshot()).append('\n');
        body.append("- 出力形式: ").append(exportEntity.getOutputFormat()).append('\n');
        if (exportEntity.getRecipientNote() != null && !exportEntity.getRecipientNote().isBlank()) {
            body.append("- 提出先メモ: ").append(exportEntity.getRecipientNote()).append('\n');
        }

        return new CreateDocumentRequest(
                title,
                body.toString(),
                mode.name(),
                null,           // priority: デフォルト NORMAL
                dueDate,
                null,           // reminderEnabled: デフォルト false
                null,           // reminderIntervalHours: デフォルト 24
                null,           // stampDisplayStyle: デフォルト STANDARD
                recipients,
                null);          // sequentialCount: HYBRID 未使用（重説回覧は SEQUENTIAL/SIMULTANEOUS のみ）
    }
}
