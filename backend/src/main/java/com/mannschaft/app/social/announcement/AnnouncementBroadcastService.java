package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.announcement.adapter.AnnouncementChannelAdapter;
import com.mannschaft.app.social.announcement.adapter.AnnouncementChannelAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F02.8 告知ウィザード実行サービス。
 *
 * <p>チャネルアダプター経由でコンテンツを作成し、{@link AnnouncementFeedService} に
 * お知らせフィードを登録する。認可チェック・テンプレート検証・target_team_ids 検証も担う。</p>
 *
 * <p>全処理は {@code @Transactional} でラップされており、フィード登録失敗時はコンテンツも
 * ロールバックされる（孤立コンテンツ発生防止）。</p>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AnnouncementBroadcastService {

    private final AnnouncementFeedService announcementFeedService;
    private final AnnouncementChannelAdapterRegistry adapterRegistry;
    private final AnnouncementRangeTemplateRepository templateRepository;
    private final AccessControlService accessControlService;
    private final UserRoleRepository userRoleRepository;

    /**
     * 告知ウィザードを実行し、コンテンツを作成してお知らせフィードに登録する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>メンバーシップ検証（スコープのメンバーであること）</li>
     *   <li>MEMBER の優先度制限チェック</li>
     *   <li>target_team_ids 検証（ORGANIZATION スコープかつ絞り込み指定がある場合）</li>
     *   <li>テンプレート検証（templateId 指定がある場合）</li>
     *   <li>チャネルアダプター呼び出し（コンテンツ作成）</li>
     *   <li>お知らせフィード登録</li>
     * </ol>
     *
     * @param req 告知ウィザード実行リクエスト
     * @return 告知ウィザード実行結果
     */
    public BroadcastResult broadcast(BroadcastRequest req) {

        // 1. メンバーシップ検証
        accessControlService.checkMembership(
                req.getCallerUserId(), req.getScopeId(), req.getScopeType());

        // 2. MEMBER の priority 制限
        boolean isAdmin = accessControlService.isAdminOrAbove(
                req.getCallerUserId(), req.getScopeId(), req.getScopeType());
        String priority = req.getPriority() != null ? req.getPriority() : "NORMAL";
        if (!isAdmin && !"NORMAL".equals(priority)) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_001);
        }

        // 3. target_team_ids 検証（ORGANIZATION スコープ かつ 絞り込みあり）
        if ("ORGANIZATION".equals(req.getScopeType())
                && req.getTargetTeamIds() != null
                && !req.getTargetTeamIds().isEmpty()) {
            validateTargetTeamIds(req.getScopeId(), req.getTargetTeamIds());
        }

        // 4. テンプレート検証
        if (req.getTemplateId() != null) {
            AnnouncementScopeType announcementScopeType =
                    AnnouncementScopeType.valueOf(req.getScopeType());
            templateRepository.findById(req.getTemplateId())
                    .filter(t -> t.getScopeType() == announcementScopeType
                              && t.getScopeId().equals(req.getScopeId()))
                    .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.BROADCAST_003));
        }

        // 5. チャネルアダプター呼び出し（コンテンツ作成）
        AnnouncementChannelAdapter adapter = adapterRegistry.getAdapter(req.getChannel());
        AnnouncementSourceType sourceType = adapter.getSourceType();

        Long contentId = adapter.createContent(
                req.getContent(),
                req.getScopeType(),
                req.getScopeId(),
                req.getTargetRole(),   // visibility は target_role をそのまま使用（設計書§7）
                req.getCallerUserId());

        String contentUrl = adapter.buildContentUrl(
                req.getScopeType(), req.getScopeId(), contentId);

        // 6. お知らせフィード登録
        AnnouncementScopeType announcementScopeType =
                AnnouncementScopeType.valueOf(req.getScopeType());

        // titleCache: コンテンツのタイトルから設定（null の場合は空文字で代替）
        String titleCache = req.getContent() != null ? req.getContent().getTitle() : null;

        AnnouncementFeedEntity feed = announcementFeedService.createFromBroadcast(
                sourceType,
                contentId,
                announcementScopeType,
                req.getScopeId(),
                req.getCallerUserId(),
                priority,
                req.getExpiresAt(),
                targetTeamIdsToJson(req.getTargetTeamIds()),
                titleCache,
                req.getTargetRole());

        log.info("告知ウィザード実行完了 feedId={}, channel={}, scopeType={}, scopeId={}",
                feed.getId(), req.getChannel(), req.getScopeType(), req.getScopeId());

        return BroadcastResult.builder()
                .announcementFeedId(feed.getId())
                .channel(req.getChannel())
                .contentId(contentId)
                .contentUrl(contentUrl)
                .targetRole(req.getTargetRole())
                .targetTeamIds(req.getTargetTeamIds())
                .priority(priority)
                .createdAt(feed.getCreatedAt())
                .build();
    }

    /**
     * target_team_ids が組織配下のチームであることを検証する（IDOR 対策）。
     *
     * <p>設計書 §11.1 に準拠し、他組織のチーム ID を指定して不正な配信対象を
     * 作ることを防ぐ二重検証を実施する。</p>
     *
     * @param organizationId 組織 ID
     * @param targetTeamIds  絞り込み対象チーム ID リスト
     */
    private void validateTargetTeamIds(Long organizationId, List<Long> targetTeamIds) {
        // 組織配下のチームIDを取得（user_roles テーブルから organization_id で絞り込み）
        Set<Long> orgTeamIds = new HashSet<>(
                userRoleRepository.findTeamIdsByOrganizationId(organizationId));
        if (!orgTeamIds.containsAll(targetTeamIds)) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_002);
        }
    }

    /**
     * {@code List<Long>} を JSON 配列文字列に変換する。null の場合は null を返す。
     *
     * @param ids チーム ID リスト
     * @return JSON 配列文字列（例: "[1,3,5]"）。ids が null の場合は null
     */
    private String targetTeamIdsToJson(List<Long> ids) {
        if (ids == null) {
            return null;
        }
        return "[" + ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
    }
}
