package com.mannschaft.app.faq.service;

import com.mannschaft.app.faq.FaqCategory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * F21.1 §5.5: 団体の種別文字列から {@link FaqCategory} を解決するリゾルバ。
 *
 * <p>チームの {@code template}（{@code com.mannschaft.app.team.entity.TeamEntity#template}、
 * VARCHAR30・自由文字列）または組織の {@code orgType}
 * （{@code OrganizationEntity.OrgType} enum 名）を受け取り、対応する {@link FaqCategory} を返す。</p>
 *
 * <h3>調査結果（teams.template の実際の保存値）</h3>
 * <p>{@code CreateTeamRequest.template} はバリデーションも変換も無く
 * {@code TeamService#createTeam} で {@code TeamEntity.template} にそのまま保存される。
 * フロントエンド（{@code EntityCreateDialog.vue} / {@code team.ts} の {@code TeamTemplate}）が
 * 送る値は<strong>大文字の列挙値</strong>
 * （{@code CLUB / CLINIC / CLASS / COMMUNITY / COMPANY / FAMILY / RESTAURANT / BEAUTY /
 * STORE / VOLUNTEER / NEIGHBORHOOD / CONDO / OTHER}）である。
 * 一方、F01.3 テンプレートモジュール設計書には小文字スラッグ
 * （{@code sports / clinic / gym / salon / apartment} 等）の例示も存在する。
 * 双方が混在しても破綻しないよう、<strong>大文字・小文字を無視し両系統を防御的にマッピング</strong>する。
 * 既知のいずれにも一致しない値・null は {@link FaqCategory#GENERAL} にフォールバックする。</p>
 *
 * <h3>組織 orgType</h3>
 * <p>{@code OrgType} enum: GOVERNMENT / MUNICIPALITY / COMPANY / HOSPITAL / ASSOCIATION /
 * SCHOOL / NPO / COMMUNITY / OTHER。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5</p>
 */
@Component
public class FaqCategoryResolver {

    /**
     * 正規化済み（小文字）キー → カテゴリの対応表。
     *
     * <p>チームの template（フロント大文字 + 小文字スラッグ）と組織の orgType（enum 名）の
     * 両系統を防御的に同居させる。値の重複（例: COMPANY はチームにも組織にも存在）は
     * 同一カテゴリへ写すため衝突しない。</p>
     */
    private static final Map<String, FaqCategory> CATEGORY_MAP = Map.ofEntries(
            // --- SPORTS ---
            Map.entry("club", FaqCategory.SPORTS),        // team: CLUB
            Map.entry("sports", FaqCategory.SPORTS),      // slug
            Map.entry("gym", FaqCategory.SPORTS),         // slug

            // --- HEALTH ---
            Map.entry("clinic", FaqCategory.HEALTH),      // team: CLINIC / slug: clinic（整骨院含む）
            Map.entry("hospital", FaqCategory.HEALTH),    // org: HOSPITAL

            // --- EDUCATION ---
            Map.entry("class", FaqCategory.EDUCATION),    // team: CLASS
            Map.entry("school", FaqCategory.EDUCATION),   // org: SCHOOL / slug: school

            // --- BUSINESS ---
            Map.entry("company", FaqCategory.BUSINESS),   // team: COMPANY / org: COMPANY / slug: company
            Map.entry("restaurant", FaqCategory.BUSINESS),// team: RESTAURANT / slug: restaurant
            Map.entry("store", FaqCategory.BUSINESS),     // team: STORE
            Map.entry("beauty", FaqCategory.BUSINESS),    // team: BEAUTY
            Map.entry("salon", FaqCategory.BUSINESS),     // slug: salon（美容サロン）

            // --- COMMUNITY ---
            Map.entry("community", FaqCategory.COMMUNITY),       // team/org: COMMUNITY / slug: community
            Map.entry("neighborhood", FaqCategory.COMMUNITY),    // team: NEIGHBORHOOD / slug: neighborhood
            Map.entry("volunteer", FaqCategory.COMMUNITY),       // team: VOLUNTEER
            Map.entry("npo", FaqCategory.COMMUNITY),             // org: NPO
            Map.entry("association", FaqCategory.COMMUNITY),     // org: ASSOCIATION
            Map.entry("government", FaqCategory.COMMUNITY),      // org: GOVERNMENT
            Map.entry("municipality", FaqCategory.COMMUNITY),    // org: MUNICIPALITY

            // --- RESIDENCE ---
            Map.entry("condo", FaqCategory.RESIDENCE),    // team: CONDO
            Map.entry("apartment", FaqCategory.RESIDENCE),// slug: apartment（マンション）
            Map.entry("family", FaqCategory.RESIDENCE),   // team: FAMILY

            // --- GENERAL（明示）---
            Map.entry("other", FaqCategory.GENERAL),      // team/org: OTHER
            Map.entry("custom", FaqCategory.GENERAL)      // slug: custom
    );

    /**
     * 種別文字列（team.template / org.orgType）から {@link FaqCategory} を解決する。
     *
     * <p>大文字・小文字を無視し、前後空白をトリムする。未知・null・空文字は
     * {@link FaqCategory#GENERAL} にフォールバックする。</p>
     *
     * @param raw チームの template または組織の orgType 名（null 可）
     * @return 解決された {@link FaqCategory}（未知は GENERAL）
     */
    public FaqCategory resolve(String raw) {
        if (raw == null) {
            return FaqCategory.GENERAL;
        }
        String key = raw.trim().toLowerCase();
        if (key.isEmpty()) {
            return FaqCategory.GENERAL;
        }
        return CATEGORY_MAP.getOrDefault(key, FaqCategory.GENERAL);
    }
}
