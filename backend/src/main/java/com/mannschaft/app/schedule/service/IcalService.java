package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.schedule.GoogleCalendarErrorCode;
import com.mannschaft.app.schedule.dto.IcalTokenResponse;
import com.mannschaft.app.schedule.dto.IcalTokenResponse.ScopedUrlItem;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.UserIcalTokenEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserIcalTokenRepository;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * iCal配信サービス。iCalトークン管理・VCALENDARフィード生成を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IcalService {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final int ICAL_MONTHS_PAST = 2;
    private static final int ICAL_MONTHS_FUTURE = 12;
    private static final int ICAL_MAX_EVENTS = 500;
    private static final String ICAL_BASE_PATH = "/ical/";
    private static final DateTimeFormatter ICAL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /**
     * フロントエンド / iCal URL の基底 URL。
     * 正準プロパティ {@code app.base-url}（環境変数 {@code APP_BASE_URL}）から注入。
     * webcal URL 生成時は {@link #buildWebcalUrl(String)} でスキームを変換して使用する。
     */
    @Value("${app.base-url}")
    private String appBaseUrl;

    private final UserIcalTokenRepository icalTokenRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRoleRepository userRoleRepository;
    private final NameResolverService nameResolverService;
    private final AccessControlService accessControlService;

    /** スコープ種別: チーム */
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    /** スコープ種別: 組織 */
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";
    /** iCal クエリのスコープ値: チーム */
    private static final String SCOPE_PARAM_TEAM = "team";
    /** iCal クエリのスコープ値: 組織 */
    private static final String SCOPE_PARAM_ORGANIZATION = "organization";
    /** スコープ配信の最低要求ロール（GUEST 不可） */
    private static final String MIN_FEED_ROLE = "SUPPORTER";

    /**
     * iCalトークンを取得する。未発行の場合は自動生成する。
     *
     * @param userId ユーザーID
     * @return トークン情報レスポンス
     */
    @Transactional
    public IcalTokenResponse getOrCreateToken(Long userId) {
        var tokenOpt = icalTokenRepository.findByUserId(userId);

        if (tokenOpt.isPresent()) {
            return buildTokenResponse(tokenOpt.get());
        }

        // 新規トークン生成
        String token = generateSecureToken();
        icalTokenRepository.insert(userId, token, true);

        var created = icalTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_NOT_FOUND));
        log.info("iCalトークン新規発行: userId={}", userId);
        return buildTokenResponse(created);
    }

    /**
     * iCalトークンを再生成する。既存トークンを新しいトークンで置き換える。
     *
     * @param userId ユーザーID
     * @return トークン情報レスポンス
     */
    @Transactional
    public IcalTokenResponse regenerateToken(Long userId) {
        icalTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_NOT_FOUND));

        String newToken = generateSecureToken();
        icalTokenRepository.updateToken(userId, newToken);

        var updated = icalTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_NOT_FOUND));
        log.info("iCalトークン再生成: userId={}", userId);
        return buildTokenResponse(updated);
    }

    /**
     * iCalトークンを物理削除する。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void deleteToken(Long userId) {
        icalTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_NOT_FOUND));

        icalTokenRepository.deleteByUserId(userId);
        log.info("iCalトークン削除: userId={}", userId);
    }

    /**
     * iCalフィードを生成する。RFC 5545準拠のVCALENDAR文字列を返す。
     *
     * @param token   iCalトークン
     * @param scope   スコープ（team / organization / personal / null=全スコープ）
     * @param scopeId スコープID（scope指定時に必須）
     * @return iCal文字列
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（fetchSchedulesForFeed内でUserRoleRepositoryを参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public String generateIcalFeed(String token, String scope, Long scopeId) {
        var tokenEntity = icalTokenRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_INVALID));

        if (!tokenEntity.getIsActive()) {
            throw new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_INVALID);
        }

        Long userId = tokenEntity.getUserId();

        // スコープ指定フィードは、トークン所有者が当該スコープに所属している場合のみ配信する。
        // （番人の委譲追跡が 2 ホップまでのため、認可呼び出しは public 入口に直接置く）
        if (scopeId != null) {
            if (SCOPE_PARAM_TEAM.equals(scope)
                    && !accessControlService.hasRoleOrAbove(userId, scopeId, SCOPE_TYPE_TEAM, MIN_FEED_ROLE)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            if (SCOPE_PARAM_ORGANIZATION.equals(scope)
                    && !accessControlService.hasRoleOrAbove(userId, scopeId, SCOPE_TYPE_ORGANIZATION, MIN_FEED_ROLE)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }

        LocalDateTime from = LocalDateTime.now().minusMonths(ICAL_MONTHS_PAST);
        LocalDateTime to = LocalDateTime.now().plusMonths(ICAL_MONTHS_FUTURE);

        // スコープに応じてスケジュールを取得
        List<ScheduleEntity> schedules = fetchSchedulesForFeed(userId, scope, scopeId, from, to);

        return buildVCalendar(schedules);
    }

    /**
     * トークンのポーリング日時を更新する。
     *
     * @param token iCalトークン
     */
    @Transactional
    public void recordPoll(String token) {
        icalTokenRepository.updateLastPolledAt(token, LocalDateTime.now());
    }

    /**
     * iCalフィードのETagを算出する。MAX(updated_at) + count のSHA-256。
     *
     * @param token   iCalトークン
     * @param scope   スコープ
     * @param scopeId スコープID
     * @return ETag文字列
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（fetchSchedulesForFeed内でUserRoleRepositoryを参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public String calculateETag(String token, String scope, Long scopeId) {
        var tokenEntity = icalTokenRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.ICAL_TOKEN_INVALID));

        Long userId = tokenEntity.getUserId();

        // generateIcalFeed と同一のスコープ認可を課す（ETag 経由での越境な件数・更新時刻の推測を防ぐ）
        if (scopeId != null) {
            if (SCOPE_PARAM_TEAM.equals(scope)
                    && !accessControlService.hasRoleOrAbove(userId, scopeId, SCOPE_TYPE_TEAM, MIN_FEED_ROLE)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            if (SCOPE_PARAM_ORGANIZATION.equals(scope)
                    && !accessControlService.hasRoleOrAbove(userId, scopeId, SCOPE_TYPE_ORGANIZATION, MIN_FEED_ROLE)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }

        LocalDateTime from = LocalDateTime.now().minusMonths(ICAL_MONTHS_PAST);
        LocalDateTime to = LocalDateTime.now().plusMonths(ICAL_MONTHS_FUTURE);

        List<ScheduleEntity> schedules = fetchSchedulesForFeed(userId, scope, scopeId, from, to);

        LocalDateTime maxUpdatedAt = schedules.stream()
                .map(ScheduleEntity::getUpdatedAt)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MIN);

        String raw = maxUpdatedAt.toString() + ":" + schedules.size();
        return sha256(raw);
    }

    // --- プライベートメソッド ---

    /**
     * セキュアなランダムトークンを生成する。
     */
    private String generateSecureToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * スコープに応じてスケジュールを取得する。
     */
    private List<ScheduleEntity> fetchSchedulesForFeed(Long userId, String scope, Long scopeId,
                                                       LocalDateTime from, LocalDateTime to) {
        if ("team".equals(scope) && scopeId != null) {
            return scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(scopeId, from, to)
                    .stream().limit(ICAL_MAX_EVENTS).toList();
        } else if ("organization".equals(scope) && scopeId != null) {
            return scheduleRepository
                    .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(scopeId, from, to)
                    .stream().limit(ICAL_MAX_EVENTS).toList();
        } else if ("personal".equals(scope)) {
            return scheduleRepository
                    .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, from, to)
                    .stream().limit(ICAL_MAX_EVENTS).toList();
        }

        List<ScheduleEntity> allSchedules = new ArrayList<>(
                scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, from, to));

        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        for (UserRoleEntity role : teamRoles) {
            allSchedules.addAll(scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), from, to));
        }

        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        for (UserRoleEntity role : orgRoles) {
            allSchedules.addAll(scheduleRepository
                    .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(role.getOrganizationId(), from, to));
        }

        return allSchedules.stream()
                .sorted((a, b) -> a.getStartAt().compareTo(b.getStartAt()))
                .limit(ICAL_MAX_EVENTS)
                .toList();
    }

    /**
     * スケジュール一覧からRFC 5545準拠のVCALENDAR文字列を生成する。
     */
    private String buildVCalendar(List<ScheduleEntity> schedules) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//Mannschaft//Schedule//JP\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:Mannschaft\r\n");

        for (ScheduleEntity schedule : schedules) {
            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:schedule-").append(schedule.getId()).append("@mannschaft.app\r\n");
            sb.append("DTSTART:").append(formatIcalDateTime(schedule.getStartAt())).append("\r\n");
            if (schedule.getEndAt() != null) {
                sb.append("DTEND:").append(formatIcalDateTime(schedule.getEndAt())).append("\r\n");
            }
            sb.append("SUMMARY:").append(escapeIcalText(schedule.getTitle())).append("\r\n");
            if (schedule.getDescription() != null) {
                sb.append("DESCRIPTION:").append(escapeIcalText(schedule.getDescription())).append("\r\n");
            }
            if (schedule.getLocation() != null) {
                sb.append("LOCATION:").append(escapeIcalText(schedule.getLocation())).append("\r\n");
            }
            sb.append("STATUS:").append(mapScheduleStatusToIcal(schedule.getStatus().name())).append("\r\n");
            if (schedule.getUpdatedAt() != null) {
                sb.append("LAST-MODIFIED:").append(formatIcalDateTime(schedule.getUpdatedAt())).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }

        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    /**
     * LocalDateTimeをiCalフォーマットに変換する。
     */
    private String formatIcalDateTime(LocalDateTime dateTime) {
        return dateTime.format(ICAL_DATE_FORMAT);
    }

    /**
     * iCalテキストプロパティのエスケープ処理を行う。
     */
    private String escapeIcalText(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /**
     * スケジュールステータスをiCalのVEVENTステータスにマッピングする。
     */
    private String mapScheduleStatusToIcal(String status) {
        return switch (status) {
            case "SCHEDULED" -> "CONFIRMED";
            case "CANCELLED" -> "CANCELLED";
            case "COMPLETED" -> "CONFIRMED";
            default -> "TENTATIVE";
        };
    }

    /**
     * SHA-256ハッシュを算出する。
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256アルゴリズムが利用できません", e);
        }
    }

    /**
     * {@code https://} または {@code http://} で始まる URL を {@code webcal://} スキームに変換する。
     *
     * <p>本番（Cloudflare 同一オリジン構成）では base-url のホストがそのまま webcal ホストになるため、
     * ホスト:ポート部分は base-url のものをそのまま使用する。</p>
     *
     * <p>例:</p>
     * <ul>
     *   <li>{@code https://app.example.com/ical/abc.ics} → {@code webcal://app.example.com/ical/abc.ics}</li>
     *   <li>{@code http://localhost:3000/ical/abc.ics} → {@code webcal://localhost:3000/ical/abc.ics}</li>
     * </ul>
     *
     * @param httpsUrl {@code https://} または {@code http://} で始まる URL
     * @return {@code webcal://} スキームに変換した URL
     */
    public String buildWebcalUrl(String httpsUrl) {
        if (httpsUrl == null) return null;
        if (httpsUrl.startsWith("https://")) {
            return "webcal://" + httpsUrl.substring("https://".length());
        }
        if (httpsUrl.startsWith("http://")) {
            return "webcal://" + httpsUrl.substring("http://".length());
        }
        // 想定外のスキームはそのまま返す（フォールバック）
        return httpsUrl;
    }

    /**
     * トークンエンティティからレスポンスDTOを構築する。
     */
    private IcalTokenResponse buildTokenResponse(UserIcalTokenEntity entity) {
        String token = entity.getToken();
        // ical エンドポイントの完全 URL（https://app.example.com/ical/{token}.ics）
        String icalUrl = appBaseUrl + ICAL_BASE_PATH + token + ".ics";
        String webcalUrl = buildWebcalUrl(icalUrl);

        Long userId = entity.getUserId();
        List<ScopedUrlItem> scopedUrls = new ArrayList<>();

        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        if (!teamRoles.isEmpty()) {
            Set<Long> teamIds = teamRoles.stream().map(UserRoleEntity::getTeamId).collect(Collectors.toSet());
            Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
            for (Long teamId : teamIds) {
                String scopedUrl = icalUrl + "?scope=team&scopeId=" + teamId;
                scopedUrls.add(new ScopedUrlItem("TEAM", teamId,
                        teamNames.getOrDefault(teamId, "チーム " + teamId),
                        scopedUrl, scopedUrl + "&action=subscribe"));
            }
        }

        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        if (!orgRoles.isEmpty()) {
            Set<Long> orgIds = orgRoles.stream().map(UserRoleEntity::getOrganizationId).collect(Collectors.toSet());
            Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);
            for (Long orgId : orgIds) {
                String scopedUrl = icalUrl + "?scope=organization&scopeId=" + orgId;
                scopedUrls.add(new ScopedUrlItem("ORGANIZATION", orgId,
                        orgNames.getOrDefault(orgId, "組織 " + orgId),
                        scopedUrl, scopedUrl + "&action=subscribe"));
            }
        }

        String personalUrl = icalUrl + "?scope=personal";
        scopedUrls.add(new ScopedUrlItem("PERSONAL", userId, "個人",
                personalUrl, personalUrl + "&action=subscribe"));

        return new IcalTokenResponse(
                token,
                icalUrl,
                icalUrl + "?action=subscribe",
                webcalUrl,
                scopedUrls,
                entity.getIsActive(),
                entity.getLastPolledAt());
    }
}
