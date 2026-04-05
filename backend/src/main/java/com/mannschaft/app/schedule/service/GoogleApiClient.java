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
     * @return 作成されたイベントのID
     */
    public String createEvent(String accessToken, String calendarId, CalendarEventRequest event) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri(calendarApiBase + "/calendars/{calendarId}/events", calendarId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .body(Map.class);
            return response != null ? (String) response.get("id") : null;
        } catch (Exception e) {
            log.error("Google Calendar event create失敗: calendarId={}", calendarId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }
    }

    /**
     * Google Calendar のイベントを更新する。
     */
    public void updateEvent(String accessToken, String calendarId, String eventId, CalendarEventRequest event) {
        try {
            restClient.patch()
                    .uri(calendarApiBase + "/calendars/{calendarId}/events/{eventId}", calendarId, eventId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Google Calendar event update失敗: eventId={}", eventId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }
    }

    // --- DTOs ---

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
