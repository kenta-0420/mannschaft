package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.dto.SpotlightContentResponse;
import com.mannschaft.app.advertising.dto.SpotlightViewRequest;
import com.mannschaft.app.advertising.dto.SpotlightViewResponse;
import com.mannschaft.app.advertising.dto.SpotlightVisitRequest;
import com.mannschaft.app.advertising.dto.SpotlightVisitResponse;
import org.springframework.stereotype.Service;

/**
 * F09.19.2 サービング・計測サービス（正本 §6.2〜6.4・§7.1〜7.5・§11）。
 *
 * <p><b>試練（red 先行）</b>: 本クラスは骨格のみ。全メソッドは {@link UnsupportedOperationException}
 * を投げる。出陣（実装）で有料プランゲート・割当ロジック・serve 証跡/serve-cap・dedupe・
 * クールダウン・IP レート制限を充填して green 化する。</p>
 */
@Service
public class SpotlightServingService {

    /**
     * 掲載面に表示する広告候補を取得する（正本 §6.2・§7.1〜7.5）。
     *
     * @param userId     認証ユーザー id
     * @param placement  掲載面（AdPlacement 名。不正は AD_003）
     * @param count      返却上限（1〜2。範囲外は AD_003）
     * @param scopeType  "PERSONAL" | "TEAM" | "ORGANIZATION"（省略時 PERSONAL）
     * @param scopeId    TEAM / ORGANIZATION 時必須（欠落は AD_003）
     * @param template   スコープテンプレート slug（任意）
     * @param prefecture 都道府県コード（任意）
     * @param locale     ロケール（既定 ja）
     * @return 候補（有料プランゲート該当・候補ゼロは items:[]）
     */
    public SpotlightContentResponse serveContent(
            Long userId, String placement, Integer count, String scopeType, Long scopeId,
            String template, String prefecture, String locale) {
        throw new UnsupportedOperationException("F09.19.2 未実装（試練 red）");
    }

    /**
     * インプレッションを計上する（正本 §6.3・§11）。
     *
     * <p>serve 証跡必須（無ければ 404）・placement 整合・deliveryId 帰属検証・600 秒 dedupe。</p>
     *
     * @return 採番 impressionId + duplicate 判定
     */
    public SpotlightViewResponse recordView(Long userId, Long creativeId,
                                            SpotlightViewRequest request) {
        throw new UnsupportedOperationException("F09.19.2 未実装（試練 red）");
    }

    /**
     * クリックを計上する（正本 §6.4・§11）。
     *
     * <p>serve 証跡必須（無ければ 404）・placement 整合・deliveryId 帰属検証・
     * 60 秒ユーザークールダウン・IP レート制限（60 秒 10 回、超過 AD_029）。</p>
     *
     * @return 採番 clickId（クールダウン中は clickId=null）
     */
    public SpotlightVisitResponse recordVisit(Long userId, Long creativeId, String ipAddress,
                                              SpotlightVisitRequest request) {
        throw new UnsupportedOperationException("F09.19.2 未実装（試練 red）");
    }
}
