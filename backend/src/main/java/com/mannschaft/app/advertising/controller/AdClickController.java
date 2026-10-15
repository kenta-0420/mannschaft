package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdClickResponse;
import com.mannschaft.app.advertising.dto.RecordAdClickRequest;
import com.mannschaft.app.advertising.service.AdClickService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * F09.7 クリック計測コントローラー。
 *
 * <p>{@code POST /api/v1/ads/{adId}/click} で広告クリックを記録する。
 * 認証不要（未ログインユーザーのクリックにも対応）。SecurityConfig で permitAll 設定済み。</p>
 *
 * <p>TODO: 将来: IP ベースのレート制限を追加して不正クリックを防止する</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers(POST, "/api/v1/ads/&#42;/click").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * 広告クリック計測は<b>未ログイン訪問者のクリックも計測対象</b>であり、認証を課すと計測が成立しない。受け取るのは広告ID・キャンペーンID等の計測値のみで、
 * 応答は採番されたクリックIDのみ。個人データ・テナント固有データを一切返さない。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic("/api/v1/ads/*/click")
@RestController
@RequestMapping("/api/v1/ads/{adId}/click")
@RequiredArgsConstructor
public class AdClickController {

    private final AdClickService adClickService;

    /**
     * 広告クリックを記録し、クリック ID と発生日時を返す。
     *
     * @param adId    ads.id（パス変数）
     * @param request クリック記録リクエスト
     * @return 作成したクリックの id と occurredAt
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdClickResponse> recordClick(
            @PathVariable Long adId,
            @Valid @RequestBody RecordAdClickRequest request) {

        Long clickId = adClickService.record(
                adId,
                request.campaignId(),
                request.impressionId(),
                request.userId());

        return ApiResponse.of(new AdClickResponse(clickId, LocalDateTime.now()));
    }
}
