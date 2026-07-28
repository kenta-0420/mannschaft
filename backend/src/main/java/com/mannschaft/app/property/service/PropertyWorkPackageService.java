package com.mannschaft.app.property.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 物件履歴パッケージ管理サービス（集約ルート）。
 *
 * <p>F09.13 設計書 §3 property_work_packages / §5 ビジネスロジック / §6 セキュリティ考慮事項
 * に対応。1 件の工事/事故/点検イベントを表すパッケージの CRUD・業者割当・F08.6 連携・
 * タグ JSON 変換・非正規化カウンタ更新を担う。</p>
 *
 * <p>本フェーズ（1-β）の実装範囲:</p>
 * <ul>
 *   <li>パッケージ CRUD（論理削除）・業者割当・予算取引リンク・添付カウンタ増減</li>
 *   <li>入力バリデーション（設計書 §6.6）— 文字数・金額範囲・日付前後関係・work_type 必須項目</li>
 *   <li>HtmlSanitizer によるサニタイズ</li>
 *   <li>F07.6 Incident 起点パッケージ生成のサービスエントリ（リスナーから呼ばれる）</li>
 * </ul>
 *
 * <p>本フェーズ範囲外（後続フェーズで実装）:</p>
 * <ul>
 *   <li>{@link #publishToTimeline(PropertyWorkPackageEntity)} — F04.1 TimelinePost 自動投稿。
 *       1-δ で {@code TimelinePostService} を inject して実体化する</li>
 *   <li>添付ファイル本体管理（{@code property_work_documents} の attach/detach は後続フェーズ）</li>
 *   <li>マスキング適用（{@link PropertyWorkPackageMaskingService} で別管理）</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PropertyWorkPackageService {

    /** 設計書 §3: scope_type CHECK 制約。 */
    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    /** 設計書 §6.6: title 上限。 */
    private static final int TITLE_MAX_LENGTH = 200;

    /** 設計書 §6.6: description 上限。 */
    private static final int DESCRIPTION_MAX_LENGTH = 10_000;

    /** 設計書 §6.6: incident_narrative 上限。 */
    private static final int INCIDENT_NARRATIVE_MAX_LENGTH = 5_000;

    /** 設計書 §6.6: tags 配列上限件数。 */
    private static final int TAGS_MAX_COUNT = 20;

    /** 設計書 §6.6: tags 各要素の最大文字数。 */
    private static final int TAG_MAX_LENGTH = 30;

    /** 設計書 §6.6: 金額の上限（約 1 兆円）。 */
    private static final long AMOUNT_MAX = 999_999_999_999L;

    /** 設計書 §5.7: 添付ファイル数の上限（パッケージあたり 50 件）。 */
    private static final int ATTACHMENT_LIMIT = 50;

    /** {@code work_type IN ('INCIDENT','DISASTER')} で incident_date 必須。 */
    private static final Set<WorkType> INCIDENT_DATE_REQUIRED_TYPES =
            Set.of(WorkType.INCIDENT, WorkType.DISASTER);

    private final PropertyWorkPackageRepository packageRepository;
    private final VendorService vendorService;
    private final ObjectMapper objectMapper;
    /**
     * F04.1 タイムライン投稿サービス。1-δ で配線。
     *
     * <p>新規パッケージ作成時に「【物件履歴】タイトル / 工事種別 / 業者名」形式の
     * テキスト投稿を自動作成し、{@link PropertyWorkPackageEntity#linkTimelinePost(Long)}
     * で双方向リンクを張る（設計書 §5.4）。</p>
     */
    private final TimelinePostService timelinePostService;

    // =========================================================================
    // DTO（Service 内 record）— Controller/DTO 層導入前の共通リクエスト形
    // =========================================================================

    /** パッケージ新規作成・更新用リクエスト。 */
    public record WorkPackageRequest(
            WorkType workType,
            String category,
            String title,
            String description,
            Long dwellingUnitId,
            Long incidentId,
            LocalDate incidentDate,
            String incidentNarrative,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            LocalDate actualStartDate,
            LocalDate actualEndDate,
            Long vendorId,
            Long estimatedAmount,
            Long contractAmount,
            Long actualAmount,
            String currency,
            Long budgetTransactionId,
            LocalDate warrantyUntil,
            Boolean isDisclosable,
            WorkPackageVisibility visibility,
            List<String> tags) {}

    // =========================================================================
    // 公開メソッド — CRUD
    // =========================================================================

    /**
     * パッケージを新規作成する。
     *
     * @throws BusinessException PROPERTY_004（入力不正）
     */
    @Transactional
    public PropertyWorkPackageEntity create(String scopeType, Long scopeId, Long createdBy,
                                            WorkPackageRequest req) {
        validateScope(scopeType, scopeId);
        validateRequest(req);

        // 業者割当: vendorId が指定されている場合は snapshot も同時保存
        // IDOR 防止: 同一 scope の vendor のみ参照可（VendorService 側で検証）
        String vendorNameSnapshot = null;
        if (req.vendorId() != null) {
            VendorEntity vendor = vendorService.getVendor(scopeType, scopeId, req.vendorId());
            vendorNameSnapshot = vendor.getName();
        }

        PropertyWorkPackageEntity entity = PropertyWorkPackageEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .dwellingUnitId(req.dwellingUnitId())
                .workType(req.workType())
                .category(req.category())
                .title(HtmlSanitizer.sanitizePlainText(req.title()))
                .description(HtmlSanitizer.sanitizeBasic(req.description()))
                .incidentId(req.incidentId())
                .incidentDate(req.incidentDate())
                .incidentNarrative(HtmlSanitizer.sanitizeBasic(req.incidentNarrative()))
                .plannedStartDate(req.plannedStartDate())
                .plannedEndDate(req.plannedEndDate())
                .actualStartDate(req.actualStartDate())
                .actualEndDate(req.actualEndDate())
                .vendorId(req.vendorId())
                .vendorNameSnapshot(vendorNameSnapshot)
                .estimatedAmount(req.estimatedAmount())
                .contractAmount(req.contractAmount())
                .actualAmount(req.actualAmount())
                .currency(req.currency() != null ? req.currency() : "JPY")
                .budgetTransactionId(req.budgetTransactionId())
                .warrantyUntil(req.warrantyUntil())
                .isDisclosable(req.isDisclosable() != null ? req.isDisclosable() : true)
                .visibility(req.visibility() != null ? req.visibility() : WorkPackageVisibility.ADMINS_ONLY)
                .status(WorkPackageStatus.PLANNED)
                .attachmentCount(0)
                .commentCount(0)
                .tags(serializeTags(req.tags()))
                .createdBy(createdBy)
                .build();

        PropertyWorkPackageEntity saved = packageRepository.save(entity);
        log.info("物件履歴パッケージ作成: id={}, scope={}/{}, workType={}, title={}",
                saved.getId(), scopeType, scopeId, saved.getWorkType(), saved.getTitle());

        // F04.1 TimelinePost 自動投稿（1-δ で TimelinePostService 経由に差し替え予定）
        publishToTimeline(saved);

        return saved;
    }

    /**
     * 既存パッケージを更新する。楽観的ロックは {@code @Version} カラムで自動管理される。
     *
     * @throws BusinessException PROPERTY_001 / PROPERTY_004
     */
    @Transactional
    public PropertyWorkPackageEntity update(Long packageId, Long updatedBy, WorkPackageRequest req) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        validateRequest(req);

        entity.updateBasicInfo(
                HtmlSanitizer.sanitizePlainText(req.title()),
                HtmlSanitizer.sanitizeBasic(req.description()),
                req.category());
        entity.updatePlannedDates(req.plannedStartDate(), req.plannedEndDate());
        entity.updateActualDates(req.actualStartDate(), req.actualEndDate());
        entity.updateAmounts(req.estimatedAmount(), req.contractAmount(), req.actualAmount());
        entity.updateWarrantyUntil(req.warrantyUntil());
        entity.updateIncidentInfo(req.incidentId(), req.incidentDate(),
                HtmlSanitizer.sanitizeBasic(req.incidentNarrative()));
        entity.updateTags(serializeTags(req.tags()));
        entity.recordUpdatedBy(updatedBy);

        if (req.visibility() != null) {
            entity.changeVisibility(req.visibility());
        }
        if (req.isDisclosable() != null) {
            entity.setDisclosable(req.isDisclosable());
        }

        // 業者変更: vendorId が変わった場合のみ snapshot を更新（同一ならそのまま）
        // IDOR 防止: パッケージ自身の scope と vendor の scope の一致を VendorService 側で検証
        if (!java.util.Objects.equals(entity.getVendorId(), req.vendorId())) {
            if (req.vendorId() != null) {
                VendorEntity vendor = vendorService.getVendor(
                        entity.getScopeType(), entity.getScopeId(), req.vendorId());
                entity.assignVendor(req.vendorId(), vendor.getName());
            } else {
                // vendorId が null へ変更された場合は snapshot もクリア
                entity.assignVendor(null, null);
            }
        }

        PropertyWorkPackageEntity saved = packageRepository.save(entity);
        log.info("物件履歴パッケージ更新: id={}, updatedBy={}", packageId, updatedBy);
        return saved;
    }

    /**
     * パッケージを 1 件取得する。
     *
     * @throws BusinessException PROPERTY_001
     */
    public PropertyWorkPackageEntity getById(Long packageId) {
        return findPackageOrThrow(packageId);
    }

    /**
     * パッケージを 1 件取得し、指定 scope に属することを検証する（認可根治戦役 Wave3-B5）。
     *
     * <p>{@code /api/v1/{scope}/{scopeId}/property-history/{packageId}} のように scope が
     * path に含まれるエンドポイントでの BOLA 対策。{@code packageId} が実在しても
     * {@code scopeType}/{@code scopeId} と一致しない場合は {@link PropertyHistoryErrorCode#PROPERTY_001}
     * （不在と同一コード）を投げ、他 scope への存在秘匿を行う。
     * Controller はこのメソッドで scope 一致を確認した後、
     * {@link com.mannschaft.app.common.AccessControlService} で認可判定を行う想定。</p>
     *
     * @throws BusinessException PROPERTY_001（不在 / scope 不一致）
     */
    public PropertyWorkPackageEntity getByIdInScope(String scopeType, Long scopeId, Long packageId) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        if (!entity.getScopeType().equals(scopeType) || !entity.getScopeId().equals(scopeId)) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_001);
        }
        return entity;
    }

    /**
     * パッケージ一覧をページング取得する。フィルタは Specification で組み立てる想定だが、
     * 1-β 段階では基本的なスコープフィルタのみを公開する（高度なフィルタは 1-δ Controller 層で）。
     */
    public Page<PropertyWorkPackageEntity> list(String scopeType, Long scopeId, Pageable pageable) {
        validateScope(scopeType, scopeId);
        return packageRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                scopeType, scopeId, pageable);
    }

    /**
     * ステータスを変更する。設計書 §5.4 により完了等で TimelinePost を更新する想定だが、
     * 本フェーズではフックメソッド呼び出しのみ（実体は 1-δ）。
     *
     * @throws BusinessException PROPERTY_001
     */
    @Transactional
    public PropertyWorkPackageEntity changeStatus(Long packageId, Long updatedBy,
                                                  WorkPackageStatus newStatus) {
        if (newStatus == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        entity.changeStatus(newStatus);
        entity.recordUpdatedBy(updatedBy);
        PropertyWorkPackageEntity saved = packageRepository.save(entity);
        log.info("物件履歴パッケージステータス変更: id={}, status={}, updatedBy={}",
                packageId, newStatus, updatedBy);
        // 1-δ: TimelinePostEditEntity に履歴記録 + 投稿編集を実施
        return saved;
    }

    /**
     * パッケージを論理削除する。
     *
     * @throws BusinessException PROPERTY_001
     */
    @Transactional
    public void softDelete(Long packageId) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        entity.softDelete();
        packageRepository.save(entity);
        log.info("物件履歴パッケージ論理削除: id={}", packageId);
    }

    // =========================================================================
    // 公開メソッド — 業者・予算取引・添付・F07.6 連携
    // =========================================================================

    /**
     * パッケージに業者を割り当てる（{@code vendor_name_snapshot} も同時保存）。
     *
     * @throws BusinessException PROPERTY_001 / PROPERTY_005
     */
    @Transactional
    public PropertyWorkPackageEntity assignVendor(Long packageId, Long vendorId) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        if (vendorId == null) {
            entity.assignVendor(null, null);
        } else {
            // IDOR 防止: パッケージ自身の scope と一致する vendor のみ参照可
            VendorEntity vendor = vendorService.getVendor(
                    entity.getScopeType(), entity.getScopeId(), vendorId);
            entity.assignVendor(vendorId, vendor.getName());
        }
        return packageRepository.save(entity);
    }

    /**
     * F08.6 BudgetTransaction を関連付ける。{@code actualAmountCache} が指定された場合は
     * 表示用キャッシュとして {@code actualAmount} に保存する（設計書 §5.3 単一情報源は
     * BudgetTransaction だが、表示パスで毎回 join しないようキャッシュを許容）。
     *
     * @throws BusinessException PROPERTY_001
     */
    @Transactional
    public PropertyWorkPackageEntity linkBudgetTransaction(Long packageId, Long budgetTransactionId,
                                                            Long actualAmountCache) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        entity.linkBudgetTransaction(budgetTransactionId);
        if (actualAmountCache != null) {
            if (actualAmountCache < 0 || actualAmountCache > AMOUNT_MAX) {
                throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
            }
            entity.updateAmounts(entity.getEstimatedAmount(), entity.getContractAmount(),
                    actualAmountCache);
        }
        return packageRepository.save(entity);
    }

    /**
     * F07.6 IncidentService からのイベント受信時にリスナーから呼ばれる、事故起点
     * パッケージ生成エントリ。重複（同一 incidentId のパッケージが既存）であれば
     * {@link Optional#empty()} を返す。
     *
     * <p>設計書 §5.2 により、Incident 確定時に {@code workType=INCIDENT} の
     * パッケージを {@code status=PLANNED} で自動生成する。</p>
     */
    @Transactional
    public Optional<PropertyWorkPackageEntity> createFromIncident(
            Long incidentId, String scopeType, Long scopeId, Long createdBy,
            String title, LocalDate incidentDate, String incidentNarrative) {
        // 重複チェック: 既に同一 incidentId のパッケージがあればスキップ
        Optional<PropertyWorkPackageEntity> existing =
                packageRepository.findByIncidentIdAndDeletedAtIsNull(incidentId);
        if (existing.isPresent()) {
            log.debug("F07.6 Incident 起点パッケージは既存のためスキップ: incidentId={}", incidentId);
            return Optional.empty();
        }
        validateScope(scopeType, scopeId);

        PropertyWorkPackageEntity entity = PropertyWorkPackageEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .workType(WorkType.INCIDENT)
                .title(HtmlSanitizer.sanitizePlainText(title))
                .incidentId(incidentId)
                .incidentDate(incidentDate)
                .incidentNarrative(HtmlSanitizer.sanitizeBasic(incidentNarrative))
                .currency("JPY")
                .isDisclosable(true)
                .visibility(WorkPackageVisibility.ADMINS_ONLY)
                .status(WorkPackageStatus.PLANNED)
                .attachmentCount(0)
                .commentCount(0)
                .createdBy(createdBy)
                .build();

        PropertyWorkPackageEntity saved = packageRepository.save(entity);
        log.info("F07.6 Incident 起点パッケージ自動生成: id={}, incidentId={}, scope={}/{}",
                saved.getId(), incidentId, scopeType, scopeId);

        // 設計書 §5.4: 新規作成時に F04.1 TimelinePost を自動投稿
        publishToTimeline(saved);

        return Optional.of(saved);
    }

    /**
     * 添付文書を紐付け、{@code attachment_count} を加算する。
     *
     * <p>本メソッドは中間テーブル {@code property_work_documents} の attach 処理と
     * 同一トランザクションで呼ばれる前提で、カウンタ増加のみを担う。実際の attach は
     * 後続フェーズで {@code PropertyWorkDocumentService} が中間テーブルに insert する。</p>
     *
     * @throws BusinessException PROPERTY_001 / PROPERTY_009（上限超過）
     */
    @Transactional
    public PropertyWorkPackageEntity attachDocument(Long packageId) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        if (entity.getAttachmentCount() >= ATTACHMENT_LIMIT) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_009);
        }
        entity.incrementAttachmentCount();
        return packageRepository.save(entity);
    }

    /**
     * 添付文書の紐付けを外し、{@code attachment_count} を減算する。
     *
     * @throws BusinessException PROPERTY_001
     */
    @Transactional
    public PropertyWorkPackageEntity detachDocument(Long packageId) {
        PropertyWorkPackageEntity entity = findPackageOrThrow(packageId);
        entity.decrementAttachmentCount();
        return packageRepository.save(entity);
    }

    /**
     * F04.1 TimelinePost 自動投稿のフックメソッド（F09.13 Phase 2-α-2 で厳密化）。
     *
     * <p>設計書 §5.4 に基づき、新規パッケージ作成時に「【物件履歴】タイトル / 工事種別 /
     * 業者名」形式のテキスト投稿を自動生成して紐付ける。{@code scopeType}/{@code scopeId}
     * はパッケージと一致させ、F00 ContentVisibilityResolver の可視性ガードと整合する。</p>
     *
     * <p><strong>F09.13 Phase 2-α-2 改修</strong>: パッケージ可視性が
     * {@link WorkPackageVisibility#ADMINS_ONLY} の場合は {@link PostStatus#DRAFT} で起票し、
     * タイムライン一覧・検索結果から除外する。それ以外は従来通り PUBLISHED で起票する。
     * これにより設計書 §5.4 の要求「ADMINS_ONLY の物件履歴はタイムラインに公開せず管理者のみ閲覧」
     * を満たす（DRAFT は {@code TimelinePostRepository} のクエリで status='PUBLISHED' 絞り込みにより
     * 一覧から自動除外される）。</p>
     *
     * <p>{@code createFromIncident()} 経由でも本メソッドを呼ぶ。リスナー/イベント発火元から
     * 同一トランザクション内で実行されるため、TimelinePost 生成失敗はパッケージ生成自体を
     * ロールバックさせる（fail-closed）。</p>
     */
    private void publishToTimeline(PropertyWorkPackageEntity entity) {
        // 設計書 §5.4: scopeType / scopeId はパッケージと一致させる
        PostScopeType scopeType = "ORGANIZATION".equals(entity.getScopeType())
                ? PostScopeType.ORGANIZATION
                : PostScopeType.TEAM;

        String content = buildTimelineContent(entity);

        // F09.13 Phase 2-α-2: ADMINS_ONLY は DRAFT、それ以外は PUBLISHED（status=null で従来挙動）
        PostStatus initialStatus = entity.getVisibility() == WorkPackageVisibility.ADMINS_ONLY
                ? PostStatus.DRAFT
                : null;

        CreatePostRequest req = new CreatePostRequest(
                content,
                scopeType.name(),
                entity.getScopeId(),
                /* postedAsType */ null,
                /* postedAsId */ null,
                /* parentId */ null,
                /* repostOfId */ null,
                /* scheduledAt */ null,
                /* poll */ null,
                /* attachments */ null,
                /* status */ initialStatus);

        try {
            // システム内部からの自動投稿のため createSystemPost を使用（メンバーシップチェックをスキップ）
            PostResponse posted = timelinePostService.createSystemPost(req, entity.getCreatedBy());
            entity.linkTimelinePost(posted.getId());
            log.info("F04.1 TimelinePost 自動投稿成功: packageId={}, postId={}, visibility={}, status={}",
                    entity.getId(), posted.getId(), entity.getVisibility(),
                    initialStatus != null ? initialStatus : "PUBLISHED");
        } catch (RuntimeException e) {
            // 設計書 §5.4 整合: TimelinePost 生成失敗時はパッケージ作成自体を中断する
            log.error("F04.1 TimelinePost 自動投稿失敗: packageId={}", entity.getId(), e);
            throw e;
        }
    }

    /**
     * タイムライン投稿の本文を組み立てる（設計書 §5.4「【物件履歴】タイトル / 工事種別 / 業者名」）。
     *
     * <p>本文末尾にパッケージへのディープリンクを埋め込む要件は設計書 §5.4 に記載があるが、
     * 本フェーズではフロント URL の合成を Service 層で持たない方針とし、
     * {@code 【物件履歴】タイトル / 工事種別 (/ 業者名) (パッケージID: id)} の形で
     * 後方互換的にディープリンクできる識別子を末尾付与する。完全 URL 形式の deeplink は
     * Phase 2 でフロントベース URL 設定（{@code app.frontend.base-url} 等）と併せて実装する。</p>
     */
    private String buildTimelineContent(PropertyWorkPackageEntity entity) {
        StringBuilder sb = new StringBuilder("【物件履歴】");
        sb.append(entity.getTitle()).append(" / ").append(entity.getWorkType());
        if (entity.getVendorNameSnapshot() != null && !entity.getVendorNameSnapshot().isBlank()) {
            sb.append(" / ").append(entity.getVendorNameSnapshot());
        }
        sb.append(" (パッケージID: ").append(entity.getId()).append(")");
        return sb.toString();
    }

    // =========================================================================
    // 内部メソッド — 検証・タグ JSON 変換
    // =========================================================================

    private PropertyWorkPackageEntity findPackageOrThrow(Long packageId) {
        return packageRepository.findByIdAndDeletedAtIsNull(packageId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_001));
    }

    private void validateScope(String scopeType, Long scopeId) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType) || scopeId == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    /**
     * 設計書 §6.6 入力バリデーション全項目を一括チェックする。
     */
    private void validateRequest(WorkPackageRequest req) {
        if (req == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        if (req.workType() == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        // title: 1-200 文字
        if (req.title() == null || req.title().isBlank() || req.title().length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        // description: 0-10,000 文字
        if (req.description() != null && req.description().length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        // incident_narrative: 0-5,000 文字
        if (req.incidentNarrative() != null
                && req.incidentNarrative().length() > INCIDENT_NARRATIVE_MAX_LENGTH) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        // tags: 上限 20 件 / 各要素 1-30 文字
        if (req.tags() != null) {
            if (req.tags().size() > TAGS_MAX_COUNT) {
                throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
            }
            for (String t : req.tags()) {
                if (t == null || t.isBlank() || t.length() > TAG_MAX_LENGTH) {
                    throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
                }
            }
        }
        // 金額: 0 以上 999_999_999_999 以下
        validateAmount(req.estimatedAmount());
        validateAmount(req.contractAmount());
        validateAmount(req.actualAmount());
        // 日付前後関係
        if (req.actualStartDate() != null && req.actualEndDate() != null
                && req.actualStartDate().isAfter(req.actualEndDate())) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        if (req.plannedStartDate() != null && req.plannedEndDate() != null
                && req.plannedStartDate().isAfter(req.plannedEndDate())) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        // INCIDENT/DISASTER 時は incident_date 必須
        if (INCIDENT_DATE_REQUIRED_TYPES.contains(req.workType()) && req.incidentDate() == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        // currency: ISO 4217 — 3 文字大文字（指定がある場合のみ）
        if (req.currency() != null && !req.currency().matches("^[A-Z]{3}$")) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    private void validateAmount(Long amount) {
        if (amount == null) {
            return;
        }
        if (amount < 0 || amount > AMOUNT_MAX) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    /**
     * タグリストを JSON 文字列にシリアライズする（List&lt;String&gt; ↔ JSON）。
     * null/空 → null、変換失敗 → PROPERTY_004。
     */
    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            log.warn("tags JSON シリアライズ失敗", e);
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    /**
     * パッケージのタグ JSON 文字列を {@code List<String>} にデシリアライズして返す。
     * 後続フェーズの DTO 変換で利用する。null/空 → 空リスト。
     */
    public List<String> deserializeTags(PropertyWorkPackageEntity entity) {
        String json = entity.getTags();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("tags JSON デシリアライズ失敗: id={}", entity.getId(), e);
            return List.of();
        }
    }
}
