package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashSet;
import java.util.Set;

/**
 * リクエストスコープで代理入力状態を保持するBean（F14.1）。
 * ProxyInputContextFilterがヘッダーを検証してactivate()し、
 * Service層はisProxy()で代理入力モードかどうかを判定する。
 *
 * <p>F08.9 P3b: 同意書で許可されたスコープ集合を保持し、決済系 Service が
 * {@link #hasScope(FeatureScope)} で要求スコープ（例 {@code PAYMENT}）の有無を検証できる。</p>
 */
@Component
@RequestScope
@Getter
public class ProxyInputContext {

    private boolean proxyMode = false;
    private Long subjectUserId;
    private Long consentId;
    private String inputSource;
    private String originalStorageLocation;

    /** 同意書で許可された機能スコープ集合（F08.9 P3b）。 */
    private final Set<FeatureScope> scopes = new HashSet<>();

    public boolean isProxy() {
        return proxyMode;
    }

    public void activate(Long subjectUserId, Long consentId,
                         String inputSource, String originalStorageLocation) {
        activate(subjectUserId, consentId, inputSource, originalStorageLocation, Set.of());
    }

    /**
     * 同意書で許可されたスコープ集合を伴って代理入力モードを有効化する（F08.9 P3b）。
     */
    public void activate(Long subjectUserId, Long consentId,
                         String inputSource, String originalStorageLocation,
                         Set<FeatureScope> scopes) {
        // 防御的バリデーション（検分 P3c 🔵）。subjectUserId / inputSource は
        // proxy_input_records の NOT NULL 列・enum 解決の前提となるため必須。
        // consentId / originalStorageLocation は後見切替（GUARDIANSHIP_SWITCH）で
        // null / 固定値を許容するため要求しない。
        if (subjectUserId == null) {
            throw new IllegalArgumentException("subjectUserId は必須です");
        }
        if (inputSource == null || inputSource.isBlank()) {
            throw new IllegalArgumentException("inputSource は必須です");
        }
        this.proxyMode = true;
        this.subjectUserId = subjectUserId;
        this.consentId = consentId;
        this.inputSource = inputSource;
        this.originalStorageLocation = originalStorageLocation;
        this.scopes.clear();
        if (scopes != null) {
            this.scopes.addAll(scopes);
        }
    }

    /**
     * 代理入力モードかつ指定スコープが同意書で許可されているかを返す（F08.9 P3b）。
     *
     * <p>代理入力モードでない場合（本人操作）は常に {@code false} を返す。
     * 決済系 Service は「代理払いには {@code PAYMENT} スコープが必須」のような
     * 要求スコープ検証にこれを使う。</p>
     */
    public boolean hasScope(FeatureScope scope) {
        return proxyMode && scope != null && scopes.contains(scope);
    }

    public void clear() {
        this.proxyMode = false;
        this.subjectUserId = null;
        this.consentId = null;
        this.inputSource = null;
        this.originalStorageLocation = null;
        this.scopes.clear();
    }
}
