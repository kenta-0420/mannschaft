package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * コンテンツ種別に対応する課金ゲート用Resolverをディスパッチする。
 */
@Component
public class ContentGateResolverRegistry {

    private final Map<String, ContentGateResolver> resolvers;

    /**
     * Resolver一覧から重複のないディスパッチ表を構築する。
     *
     * @param resolverList 各コンテンツ機能が提供するResolver
     */
    public ContentGateResolverRegistry(List<ContentGateResolver> resolverList) {
        this.resolvers = resolverList.stream()
                .collect(Collectors.toUnmodifiableMap(ContentGateResolver::contentType, Function.identity()));
        if (!resolvers.keySet().containsAll(ContentGateType.SUPPORTED)) {
            throw new IllegalStateException("課金ゲートResolverが不足しています: supported="
                    + ContentGateType.SUPPORTED + ", registered=" + resolvers.keySet());
        }
    }

    /**
     * 指定コンテンツが対象スコープ内に存在するか判定する。
     */
    public boolean existsInScope(String contentType, Long contentId, Long teamId, Long organizationId) {
        ContentGateResolver resolver = resolvers.get(contentType);
        return resolver != null && resolver.existsInScope(contentId, teamId, organizationId);
    }

    public Optional<ContentGateTarget> resolveForAccess(String contentType, Long contentId) {
        ContentGateResolver resolver = resolvers.get(contentType);
        return resolver == null ? Optional.empty() : resolver.resolveForAccess(contentId);
    }
}
