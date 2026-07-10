package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.GoogleCalendarErrorCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google OAuth / Calendar REST API クライアント。
 * Spring の RestClient を使用して Google API を直接呼び出す。
 */
@Slf4j
@Component
public class GoogleApiClient {

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUri;
    private final String revokeUri;
    private final String calendarApiBase;
    private final String userinfoUri;

    public GoogleApiClient(
            @Value("${mannschaft.google.client-id}") String clientId,
            @Value("${mannschaft.google.client-secret}") String clientSecret,
            @Value("${mannschaft.google.token-uri}") String tokenUri,
            @Value("${mannschaft.google.revoke-uri}") String revokeUri,
            @Value("${mannschaft.google.calendar-api-base}") String calendarApiBase,
            @Value("${mannschaft.google.userinfo-uri}") String userinfoUri) {
        this.restClient = RestClient.create();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenUri = tokenUri;
        this.revokeUri = revokeUri;
        this.calendarApiBase = calendarApiBase;
        this.userinfoUri = userinfoUri;
    }

    /**
     * 認可コードからアクセストークンとリフレッシュトークンを取得する。
     */
    public TokenResponse exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri", redirectUri);
        params.add("grant_type", "authorization_code");

        try {
            return restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (Exception e) {
            log.error("Google OAuth token exchange失敗", e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_OAUTH_FAILED, e);
        }
    }

    /**
     * リフレッシュトークンから新しいアクセストークンを取得する。
     */
    public TokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("refresh_token", refreshToken);
        params.add("grant_type", "refresh_token");

        try {
            return restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (Exception e) {
            log.error("Google access token refresh失敗", e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_OAUTH_FAILED, e);
        }
    }

    /**
     * トークンを無効化する。
     */
    public void revokeToken(String token) {
        try {
            restClient.post()
                    .uri(revokeUri + "?token=" + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Google token revoke完了");
        } catch (Exception e) {
            log.warn("Google token revoke失敗（無視して続行）", e);
        }
    }

    /**
     * ユーザー情報（メールアドレス）を取得する。
     */
    public UserInfoResponse getUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri(userinfoUri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(UserInfoResponse.class);
        } catch (Exception e) {
            log.error("Google userinfo取得失敗", e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_OAUTH_FAILED, e);
        }
    }

    /**
     * Google Calendar にイベントを作成する。
     *
     * @return 作成されたイベントのID と ETag を含むレスポンス
     */
    public CreateEventResponse createEvent(String accessToken, String calendarId, CalendarEventRequest event) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(calendarApiBase + "/calendars/{calendarId}/events", calendarId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .body(Map.class);
            String eventId = response != null ? (String) response.get("id") : null;
            String etag = response != null ? (String) response.get("etag") : null;
            return new CreateEventResponse(eventId, etag);
        } catch (Exception e) {
            log.error("Google Calendar event create失敗: calendarId={}", calendarId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }
    }

    /**
     * Google Calendar のイベントを更新する。
     *
     * @return 更新されたイベントの ETag（取得できなかった場合は null）
     */
    public String updateEvent(String accessToken, String calendarId, String eventId, CalendarEventRequest event) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.patch()
                    .uri(calendarApiBase + "/calendars/{calendarId}/events/{eventId}", calendarId, eventId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .body(Map.class);
            return response != null ? (String) response.get("etag") : null;
        } catch (Exception e) {
            log.error("Google Calendar event update失敗: eventId={}", eventId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }
    }

    /**
     * Google Calendar のイベントを削除する（堅牢化フェーズ: トグルOFF / 退会連動 / キャンセル同期用）。
     *
     * <p><b>べき等性</b>: 対象イベントが既に存在しない（HTTP 404/410 相当）場合は「削除成功」として扱い、
     * 例外を送出しない。それ以外の API エラーは {@link BusinessException}
     * （{@link GoogleCalendarErrorCode#GOOGLE_API_ERROR}）を送出し、症状を握り潰さない
     * （CLAUDE.md 障害対応の原則）。</p>
     *
     * <p><b>未実装（堅牢化フェーズ 試練の red 用スケルトン）</b>: 本メソッドは受け入れテスト
     * （AC-6 / AC-8 / AC-9）が参照するシグネチャのみ先行定義したもので、ロジックは出陣フェーズで実装する。</p>
     *
     * @param accessToken アクセストークン
     * @param calendarId  カレンダー ID（"primary" 等）
     * @param eventId     削除対象の Google イベント ID
     */
    public void deleteEvent(String accessToken, String calendarId, String eventId) {
        throw new UnsupportedOperationException(
                "未実装: GoogleApiClient.deleteEvent（Google カレンダー同期 堅牢化フェーズ 出陣で実装）");
    }

    /**
     * Google Calendar Watch API でプッシュ通知チャンネルを登録する。
     *
     * @param calendarId  カレンダー ID（"primary" 等）
     * @param channelId   チャンネル ID（UUID）
     * @param token       チャンネルトークン（CSRF 防止用）
     * @param webhookUrl  プッシュ通知を受け取る URL
     * @return 登録されたチャンネル情報
     */
    public WatchChannelResponse watchCalendar(
            String accessToken, String calendarId,
            String channelId, String token, String webhookUrl) {
        // Google Calendar API の expiration は UNIX ミリ秒（現在時刻 + 6日23時間）
        long expirationMs = System.currentTimeMillis() + (6L * 24 * 60 * 60 * 1000) + (23L * 60 * 60 * 1000);
        Map<String, Object> body = Map.of(
                "id", channelId,
                "type", "web_hook",
                "address", webhookUrl,
                "token", token,
                "expiration", String.valueOf(expirationMs)
        );
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(calendarApiBase + "/calendars/{calendarId}/events/watch", calendarId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR);
            }
            String resourceId = (String) response.get("resourceId");
            long expMs = Long.parseLong(String.valueOf(response.get("expiration")));
            LocalDateTime expiresAt = LocalDateTime.ofEpochSecond(
                    expMs / 1000,
                    (int) ((expMs % 1000) * 1_000_000),
                    java.time.ZoneOffset.UTC);
            return new WatchChannelResponse(channelId, resourceId, expiresAt);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google Calendar watch失敗: calendarId={}", calendarId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }
    }

    /**
     * Google Calendar Channels.stop API でプッシュ通知チャンネルを停止する。
     * ベストエフォート（失敗しても処理を継続する）。
     *
     * @param accessToken アクセストークン
     * @param channelId   チャンネル ID
     * @param resourceId  リソース ID
     */
    public void stopChannel(String accessToken, String channelId, String resourceId) {
        Map<String, Object> body = Map.of(
                "id", channelId,
                "resourceId", resourceId
        );
        try {
            restClient.post()
                    .uri(calendarApiBase + "/channels/stop")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Google Calendar チャンネル停止完了: channelId={}", channelId);
        } catch (Exception e) {
            log.warn("Google Calendar チャンネル停止失敗（ベストエフォート・無視）: channelId={}", channelId, e);
        }
    }

    /**
     * Google Calendar Events.list API で updatedMin 以降に更新されたイベント一覧を取得する。
     *
     * @param accessToken アクセストークン
     * @param calendarId  カレンダー ID
     * @param updatedMin  この日時以降に更新されたイベントを返す
     * @return イベント一覧
     */
    @SuppressWarnings("unchecked")
    public List<GoogleCalendarEvent> listUpdatedEvents(
            String accessToken, String calendarId, LocalDateTime updatedMin) {
        String updatedMinStr = updatedMin.atOffset(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> java.net.URI.create(
                            calendarApiBase + "/calendars/" + java.net.URLEncoder.encode(calendarId, java.nio.charset.StandardCharsets.UTF_8)
                                    + "/events"
                                    + "?showDeleted=true"
                                    + "&orderBy=updated"
                                    + "&maxResults=50"
                                    + "&updatedMin=" + java.net.URLEncoder.encode(updatedMinStr, java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return List.of();
            }
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
            if (items == null) {
                return List.of();
            }
            List<GoogleCalendarEvent> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                result.add(GoogleCalendarEvent.fromMap(item));
            }
            return result;
        } catch (Exception e) {
            log.error("Google Calendar events.list失敗: calendarId={}", calendarId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }
    }

    // --- DTOs ---

    /**
     * Google Calendar イベント作成レスポンス（eventId + ETag）。
     */
    public record CreateEventResponse(String eventId, String etag) {}

    /**
     * Google Calendar Watch API レスポンス。
     */
    public record WatchChannelResponse(String channelId, String resourceId, LocalDateTime expiresAt) {}

    /**
     * Google Calendar イベント（Events.list / Events.get から変換）。
     */
    public static class GoogleCalendarEvent {
        private final String id;
        private final String status;
        private final String summary;
        private final String location;
        private final String etag;
        private final String recurringEventId;
        private final EventDateTime start;
        private final EventDateTime end;

        public GoogleCalendarEvent(String id, String status, String summary, String location,
                                   String etag, String recurringEventId,
                                   EventDateTime start, EventDateTime end) {
            this.id = id;
            this.status = status;
            this.summary = summary;
            this.location = location;
            this.etag = etag;
            this.recurringEventId = recurringEventId;
            this.start = start;
            this.end = end;
        }

        public String getId() { return id; }
        public String getStatus() { return status; }
        public String getSummary() { return summary; }
        public String getLocation() { return location; }
        public String getEtag() { return etag; }
        public String getRecurringEventId() { return recurringEventId; }
        public EventDateTime getStart() { return start; }
        public EventDateTime getEnd() { return end; }

        @SuppressWarnings("unchecked")
        public static GoogleCalendarEvent fromMap(Map<String, Object> map) {
            String id = (String) map.get("id");
            String status = (String) map.get("status");
            String summary = (String) map.get("summary");
            String location = (String) map.get("location");
            String etag = (String) map.get("etag");
            String recurringEventId = (String) map.get("recurringEventId");

            Map<String, Object> startMap = (Map<String, Object>) map.get("start");
            Map<String, Object> endMap = (Map<String, Object>) map.get("end");

            EventDateTime start = startMap != null
                    ? new EventDateTime((String) startMap.get("dateTime"), (String) startMap.get("date"))
                    : null;
            EventDateTime end = endMap != null
                    ? new EventDateTime((String) endMap.get("dateTime"), (String) endMap.get("date"))
                    : null;

            return new GoogleCalendarEvent(id, status, summary, location, etag, recurringEventId, start, end);
        }
    }

    /**
     * Google Calendar イベントの日時（dateTime または date）。
     */
    public static class EventDateTime {
        private final String dateTime;
        private final String date;

        public EventDateTime(String dateTime, String date) {
            this.dateTime = dateTime;
            this.date = date;
        }

        /** RFC3339 形式の dateTime（null の場合は全日予定）。 */
        public String getDateTime() { return dateTime; }
        /** YYYY-MM-DD 形式の date（全日予定の場合のみ非 null）。 */
        public String getDate() { return date; }
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;
        @JsonProperty("refresh_token")
        private String refreshToken;
        @JsonProperty("expires_in")
        private int expiresIn;
        @JsonProperty("token_type")
        private String tokenType;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserInfoResponse {
        private String email;
        private String name;
    }

    /**
     * Google Calendar Event 作成/更新リクエスト。
     */
    public record CalendarEventRequest(
            String summary,
            String description,
            String location,
            DateTimeValue start,
            DateTimeValue end
    ) {
        public record DateTimeValue(String dateTime, String timeZone) {
            public static DateTimeValue of(LocalDateTime dt, String tz) {
                return new DateTimeValue(dt.toString(), tz);
            }
        }
    }
}
