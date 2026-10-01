package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.social.announcement.adapter.AnnouncementChannelAdapter;
import com.mannschaft.app.social.announcement.adapter.AnnouncementChannelAdapterRegistry;
import com.mannschaft.app.social.announcement.audience.ResolvedBroadcastAudience;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F02.8 告知ウィザード実行サービス。
 *
 * <p>チャネルアダプター経由でコンテンツを作成し、{@link AnnouncementFeedService} に
 * お知らせフィードを登録する。認可チェック・テンプレート検証と、解決済みの宛先の保存も担う
 * （宛先の検証・展開は {@code BroadcastAudienceResolver} がトランザクションの外で行う。F01.2.1 §8）。</p>
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
    private final ObjectMapper objectMapper;

    /**
     * 告知ウィザードを実行し、コンテンツを作成してお知らせフィードに登録する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>メンバーシップ検証（スコープのメンバーであること）</li>
     *   <li>MEMBER の優先度制限チェック</li>
     *   <li>解決済みの宛先の取り出し（検証・展開は {@code BroadcastAudienceResolver} が事前に行う。F01.2.1 §8）</li>
     *   <li>テンプレート検証（templateId 指定がある場合）</li>
     *   <li>チャネルアダプター呼び出し（コンテンツ作成）</li>
     *   <li>お知らせフィード登録と宛先の記録（target_group_ids・target_audience・スナップショット）</li>
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

        // 2.5. MEMBER の target_role 制限
        // MEMBER は自スコープの内輪（MEMBERS_AND_ABOVE）にしか告知できない。
        // SUPPORTERS_AND_ABOVE / PUBLIC はスコープの所属者を越えて可視になり、
        // 事実上の「組織／チーム公式発信」として機能するため ADMIN/DEPUTY_ADMIN 以上に限定する
        // （設計書 F02.8 §3 は priority のみ言及し target_role の権限線引きを定めていなかったため、
        // 　根治として本チェックを追加。判断根拠は PR 説明を参照）。
        if (!isAdmin && !AnnouncementVisibility.MEMBERS_AND_ABOVE.equals(req.getTargetRole())) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_005);
        }

        // 3. 宛先（F01.2.1 §8）。検証と展開は BroadcastAudienceResolver がトランザクションの外で済ませている
        //    （組織・チーム・グループは別ドメインのため）。ここでは解決済みの値だけを使う。
        ResolvedBroadcastAudience audience = requireResolvedAudience(req);

        // 4. テンプレート検証
        if (req.getTemplateId() != null) {
            AnnouncementScopeType announcementScopeType =
                    EnumInputParser.parse(AnnouncementScopeType.class, req.getScopeType(), "scopeType");
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
                EnumInputParser.parse(AnnouncementScopeType.class, req.getScopeType(), "scopeType");

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
                targetTeamIdsToJson(storedTargetTeamIds(req, audience)),
                titleCache,
                req.getTargetRole());

        // 6.5. 宛先の記録（target_group_ids・include_unassigned・target_audience・グループ宛てのスナップショット）
        if (audience.mode() != ResolvedBroadcastAudience.Mode.ALL) {
            announcementFeedService.recordBroadcastAudience(
                    feed.getId(),
                    audience.mode() == ResolvedBroadcastAudience.Mode.GROUPS ? toJson(audience.groupIds()) : null,
                    audience.includeUnassigned(),
                    toJson(audience.targetAudience()),
                    audience.groupTeams());
        }

        log.info("告知ウィザード実行完了 feedId={}, channel={}, scopeType={}, scopeId={}",
                feed.getId(), req.getChannel(), req.getScopeType(), req.getScopeId());

        return BroadcastResult.builder()
                .announcementFeedId(feed.getId())
                .channel(req.getChannel())
                .contentId(contentId)
                .contentUrl(contentUrl)
                .targetRole(req.getTargetRole())
                .targetTeamIds(storedTargetTeamIds(req, audience))
                .targetGroupIds(audience.mode() == ResolvedBroadcastAudience.Mode.GROUPS ? audience.groupIds() : null)
                .includeUnassigned(audience.includeUnassigned())
                .targetAudience(audience.targetAudience())
                .priority(priority)
                .createdAt(feed.getCreatedAt())
                .build();
    }

    /**
     * 解決済みの宛先を取り出す（F01.2.1 §8.3）。
     *
     * <p>組織告知で宛先を絞るのに解決済みの宛先が渡されていなければ、検証（他組織・未加盟チームの混入の防止）を
     * 経ていない呼び出しなので拒否する。TEAM スコープと「すべてのチーム」は {@code ALL} として扱う。</p>
     */
    private static ResolvedBroadcastAudience requireResolvedAudience(BroadcastRequest req) {
        if (req.getAudience() != null) {
            return req.getAudience();
        }
        boolean narrowed = req.getTargetTeamIds() != null && !req.getTargetTeamIds().isEmpty();
        if ("ORGANIZATION".equals(req.getScopeType()) && narrowed) {
            throw new IllegalStateException(
                    "組織告知の宛先は BroadcastAudienceResolver で解決してから渡すこと（未検証の targetTeamIds）");
        }
        return ResolvedBroadcastAudience.unrestricted();
    }

    /**
     * {@code target_team_ids} に保存する値。組織告知は検証済み（重複排除済み）の「チームを選ぶ」だけを保存し、
     * TEAM スコープは従来どおりリクエストの値をそのまま保存する。
     */
    private static List<Long> storedTargetTeamIds(BroadcastRequest req, ResolvedBroadcastAudience audience) {
        if (!"ORGANIZATION".equals(req.getScopeType())) {
            return req.getTargetTeamIds();
        }
        return audience.mode() == ResolvedBroadcastAudience.Mode.TEAMS ? audience.targetTeamIds() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("告知の宛先を JSON に変換できません", e);
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
