package com.mannschaft.app.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.dto.AdminBusinessAlertSummaryResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.event.InquiryChannelChangedEvent;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.service.ModuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 業務アラートサマリーサービス（F10.7）。
 *
 * <p>認証済み ADMIN/DEPUTY_ADMIN ユーザーが管理するチームの予約・問い合わせ件数を集計し、
 * Valkey（Redis 互換）に 60 秒間キャッシュして返す。</p>
 *
 * <p>設計書: docs/features/F10.7_admin_business_alert.md §5.1</p>
 *
 * <p>TODO: admin ドメインが reservation / chat / template ドメインをまたいでいる。
 * 将来は各ドメインからのイベント購読に切り替えて依存を逆転させる候補。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminBusinessAlertService {

    private static final String CACHE_KEY_PREFIX = "admin_alert_summary:";
    private static final long CACHE_TTL_SECONDS = 60L;
    private static final String RESERVATION_MODULE_SLUG = "reservation";
    private static final String MANAGE_RESERVATIONS_PERMISSION = "MANAGE_RESERVATIONS";
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final UserRoleRepository userRoleRepository;
    private final ReservationRepository reservationRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final TeamRepository teamRepository;
    private final ModuleService moduleService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 業務アラートサマリーを返す。Valkey に 60 秒間キャッシュする。
     *
     * @param userId 認証済みユーザーの ID
     * @return 業務アラートサマリーレスポンス
     */
    public AdminBusinessAlertSummaryResponse getSummary(Long userId) {
        String cacheKey = CACHE_KEY_PREFIX + userId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, AdminBusinessAlertSummaryResponse.class);
            } catch (Exception e) {
                log.warn("業務アラートキャッシュのデシリアライズ失敗: userId={}", userId, e);
            }
        }

        AdminBusinessAlertSummaryResponse response = buildSummary(userId);

        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(response),
                    CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("業務アラートキャッシュの書き込み失敗: userId={}", userId, e);
        }

        return response;
    }

    /**
     * 指定ユーザーの業務アラートキャッシュを削除する。
     *
     * <p>新着通知（RESERVATION_RECEIVED / RESERVATION_PENDING_APPROVAL / INQUIRY_RECEIVED）
     * 生成時に呼び出してカウントの即時反映を促す。</p>
     *
     * @param userId 対象ユーザー ID
     */
    public void invalidateCache(Long userId) {
        redisTemplate.delete(CACHE_KEY_PREFIX + userId);
    }

    /**
     * inquiry チャンネル設定変更時に、該当チームの ADMIN/DEPUTY_ADMIN ユーザーの
     * 業務アラートキャッシュを非同期で削除する。
     *
     * <p>{@link com.mannschaft.app.chat.service.ChatChannelService#updateInquiryChannel} が
     * {@link InquiryChannelChangedEvent} を発行した直後に呼び出される。
     * これにより、inquiry チャンネル設定変更がウィジェットに即時反映される（F10.7）。</p>
     *
     * @param event inquiry チャンネル変更イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。問い合わせチャネル変更の管理者アラート。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @EventListener
    public void onInquiryChannelChanged(InquiryChannelChangedEvent event) {
        // ADMIN ユーザーのキャッシュを削除
        List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByTeamId(event.getTeamId());
        log.debug("業務アラートキャッシュ削除: teamId={}, channelId={}, 対象ユーザー数={}",
                event.getTeamId(), event.getChannelId(), adminUserIds.size());
        for (Long userId : adminUserIds) {
            invalidateCache(userId);
        }
    }

    // -------------------------------------------------------------------------
    // private
    // -------------------------------------------------------------------------

    private AdminBusinessAlertSummaryResponse buildSummary(Long userId) {
        // 1. ユーザーが ADMIN/DEPUTY_ADMIN を持つチーム ID 一覧を取得
        List<Long> allAdminTeamIds = userRoleRepository.findAdminAndDeputyAdminTeamIds(userId);
        if (allAdminTeamIds.isEmpty()) {
            return emptyResponse();
        }

        // 2. 予約カウント対象チームを絞り込む
        //    ADMIN のチームは無条件対象
        //    DEPUTY_ADMIN のチームは MANAGE_RESERVATIONS 権限保有時のみ
        // N+1 になるが管理チーム数は通常少ない（< 10 チーム）ため許容する。
        // TODO: チーム群一括判定クエリを追加して最適化する候補
        Set<Long> reservationTargetTeamIds = allAdminTeamIds.stream()
                .filter(teamId -> isReservationTarget(userId, teamId))
                .collect(Collectors.toSet());

        // 3. チームエンティティ取得（名前の解決用）
        List<TeamEntity> teams = teamRepository.findAllById(allAdminTeamIds);
        Map<Long, TeamEntity> teamMap = teams.stream()
                .collect(Collectors.toMap(TeamEntity::getId, t -> t));

        // 4. 予約モジュール有効状態をチームごとに取得
        Map<Long, Boolean> moduleEnabledMap = new HashMap<>();
        for (Long teamId : allAdminTeamIds) {
            moduleEnabledMap.put(teamId, moduleService.isModuleEnabledForTeam(RESERVATION_MODULE_SLUG, teamId));
        }

        // 5. 予約件数を一括集計（予約モジュール有効 かつ 予約カウント対象チームのみ）
        List<Long> reservationCountTargetIds = allAdminTeamIds.stream()
                .filter(teamId -> Boolean.TRUE.equals(moduleEnabledMap.get(teamId))
                        && reservationTargetTeamIds.contains(teamId))
                .collect(Collectors.toList());

        Map<Long, Integer> newReservationsMap = new HashMap<>();
        Map<Long, Integer> pendingApprovalMap = new HashMap<>();

        if (!reservationCountTargetIds.isEmpty()) {
            // 本日 0:00:00 JST を UTC に変換
            LocalDateTime todayStartJst = LocalDate.now(JST).atStartOfDay();
            LocalDateTime todayStartUtc = todayStartJst.atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            List<Object[]> todayConfirmed = reservationRepository
                    .countTodayConfirmedByTeamIds(reservationCountTargetIds, todayStartUtc);
            for (Object[] row : todayConfirmed) {
                Long teamId = ((Number) row[0]).longValue();
                int count = ((Number) row[1]).intValue();
                newReservationsMap.put(teamId, count);
            }

            List<Object[]> pending = reservationRepository.countPendingByTeamIds(reservationCountTargetIds);
            for (Object[] row : pending) {
                Long teamId = ((Number) row[0]).longValue();
                int count = ((Number) row[1]).intValue();
                pendingApprovalMap.put(teamId, count);
            }
        }

        // 6. 問い合わせチャンネルと未読件数を一括集計
        List<ChatChannelEntity> inquiryChannels =
                chatChannelRepository.findByTeamIdInAndIsInquiryChannelTrue(allAdminTeamIds);
        Map<Long, ChatChannelEntity> inquiryChannelByTeamId = inquiryChannels.stream()
                .collect(Collectors.toMap(ChatChannelEntity::getTeamId, c -> c));

        Map<Long, Integer> unreadInquiriesMap = new HashMap<>();
        if (!inquiryChannels.isEmpty()) {
            List<Long> channelIds = inquiryChannels.stream()
                    .map(ChatChannelEntity::getId)
                    .collect(Collectors.toList());
            int totalUnread = chatChannelMemberRepository
                    .sumUnreadCountByUserIdAndChannelIds(userId, channelIds);
            // チャンネルごとの内訳を取得するには個別クエリが必要だが、
            // 設計書 §5.1 では「チャンネルごとの個別クエリは避け、N+1 を回避」とある。
            // 一括集計値をチャンネル数で等分するのではなく、
            // 各チャンネルの未読数を個別に取得することで正確な表示を実現する。
            // チャンネル数は通常少ない（各チーム最大1本）ため個別取得のオーバーヘッドは許容できる。
            for (ChatChannelEntity channel : inquiryChannels) {
                int channelUnread = chatChannelMemberRepository
                        .sumUnreadCountByUserIdAndChannelIds(userId, List.of(channel.getId()));
                unreadInquiriesMap.put(channel.getTeamId(), channelUnread);
            }
        }

        // 7. レスポンスを組み立て
        List<AdminBusinessAlertSummaryResponse.TeamAlert> teamAlerts = new ArrayList<>();
        int totalPending = 0;

        for (Long teamId : allAdminTeamIds) {
            TeamEntity team = teamMap.get(teamId);
            if (team == null) {
                continue;
            }
            boolean reservationModuleEnabled = Boolean.TRUE.equals(moduleEnabledMap.get(teamId));

            int newReservations = reservationModuleEnabled
                    ? newReservationsMap.getOrDefault(teamId, 0) : 0;
            int pendingApproval = reservationModuleEnabled
                    ? pendingApprovalMap.getOrDefault(teamId, 0) : 0;
            int unreadInquiries = unreadInquiriesMap.getOrDefault(teamId, 0);

            totalPending += newReservations + pendingApproval + unreadInquiries;

            ChatChannelEntity inquiryChannel = inquiryChannelByTeamId.get(teamId);
            String inquiryChannelUrl = inquiryChannel != null
                    ? "/teams/" + teamId + "/chat?channel=" + inquiryChannel.getId()
                    : null;

            teamAlerts.add(AdminBusinessAlertSummaryResponse.TeamAlert.builder()
                    .teamId(teamId)
                    .teamName(team.getName())
                    .reservationModuleEnabled(reservationModuleEnabled)
                    .alerts(AdminBusinessAlertSummaryResponse.Alerts.builder()
                            .newReservations(newReservations)
                            .pendingApproval(pendingApproval)
                            .unreadInquiries(unreadInquiries)
                            .build())
                    .links(AdminBusinessAlertSummaryResponse.Links.builder()
                            .reservationsUrl("/teams/" + teamId + "/reservations")
                            .inquiryChannelUrl(inquiryChannelUrl)
                            .build())
                    .build());
        }

        return AdminBusinessAlertSummaryResponse.builder()
                .data(AdminBusinessAlertSummaryResponse.Data.builder()
                        .teams(teamAlerts)
                        .totalPending(totalPending)
                        .build())
                .build();
    }

    /**
     * 指定ユーザーが指定チームで予約カウント対象かを判定する。
     *
     * <p>ADMIN は無条件対象。DEPUTY_ADMIN は MANAGE_RESERVATIONS 権限保有時のみ対象。</p>
     */
    private boolean isReservationTarget(Long userId, Long teamId) {
        List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByTeamId(teamId);
        if (adminUserIds.contains(userId)) {
            return true;
        }
        // DEPUTY_ADMIN で MANAGE_RESERVATIONS 権限を持つかを確認
        List<Long> deputyWithPermission = userRoleRepository
                .findDeputyAdminUserIdsByTeamIdAndPermission(teamId, MANAGE_RESERVATIONS_PERMISSION);
        return deputyWithPermission.contains(userId);
    }

    private AdminBusinessAlertSummaryResponse emptyResponse() {
        return AdminBusinessAlertSummaryResponse.builder()
                .data(AdminBusinessAlertSummaryResponse.Data.builder()
                        .teams(List.of())
                        .totalPending(0)
                        .build())
                .build();
    }
}
