package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 注入された業務 resolver の安定 key と、初回の pure scalar 識別を管理する内部窓口。
 *
 * <p>永続 key は登録済み Bean の検索だけに使い、任意クラスをロードしない。
 * 業務 Repository や現在受信者の評価を呼ばず、store hook では初回 identify だけを使用する。</p>
 */
public final class ModuleEventImpactResolverRegistry {
    private static final Pattern RESOLVER_KEY_PATTERN = Pattern.compile("[a-z][a-z0-9.-]{0,99}");

    private final Map<String, ModuleEventImpactResolver> providersByKey;

    public ModuleEventImpactResolverRegistry(List<ModuleEventImpactResolver> providers) {
        if (providers == null) {
            throw new IllegalArgumentException("resolver 集合は必須");
        }
        Map<String, ModuleEventImpactResolver> registered = new LinkedHashMap<>();
        for (ModuleEventImpactResolver provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException("null の resolver は登録できない");
            }
            String key = provider.resolverKey();
            if (!validKey(key)) {
                throw new IllegalArgumentException("resolver key の長さまたは形式が不正");
            }
            if (registered.putIfAbsent(key, provider) != null) {
                throw new IllegalArgumentException("resolver key が重複している");
            }
        }
        providersByKey = Collections.unmodifiableMap(registered);
    }

    /** 0 対象は empty、1 対象だけを検証して返し、複数対象・不正返却は拒否する。 */
    public Optional<ModuleEventImpactBinding> identify(ReliableEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("識別するイベントは必須");
        }
        ModuleEventImpactBinding matched = null;
        for (Map.Entry<String, ModuleEventImpactResolver> entry : providersByKey.entrySet()) {
            Optional<ModuleEventImpactBinding> result = entry.getValue().identify(event);
            if (result == null) {
                throw new IllegalStateException("resolver が null の識別結果を返した");
            }
            if (result.isEmpty()) {
                continue;
            }
            ModuleEventImpactBinding binding = result.get();
            validateBinding(binding, entry.getKey());
            if (matched != null) {
                throw new IllegalStateException("同じイベントへ複数の resolver が適合した");
            }
            matched = binding;
        }
        return Optional.ofNullable(matched);
    }

    /** 現在評価用の内部検索。永続 key をクラス名と解釈しない。 */
    Optional<ModuleEventImpactResolver> find(String resolverKey) {
        return Optional.ofNullable(providersByKey.get(resolverKey));
    }

    /** 登録時の返却値と、永続化後の現在評価で同じ scalar 整合条件を使う。 */
    void validateBinding(ModuleEventImpactBinding binding, String expectedKey) {
        if (binding == null || !validKey(binding.getResolverKey())
                || !binding.getResolverKey().equals(expectedKey)
                || binding.getOperationId() == null || binding.getEpisodeId() == null
                || binding.getScopeType() == null || !positive(binding.getScopeId())) {
            throw new IllegalStateException("業務影響 binding の識別情報が不正");
        }
        Long organizationId = binding.getOrganizationId();
        if (organizationId != null && !positive(organizationId)) {
            throw new IllegalStateException("業務影響 binding の組織 ID が不正");
        }
        if (binding.getScopeType() == ModuleEventNoticeScope.ORGANIZATION
                && !binding.getScopeId().equals(organizationId)) {
            throw new IllegalStateException("組織 scope と tenant が一致しない");
        }
    }

    private static boolean validKey(String key) {
        return key != null && !key.isEmpty()
                && key.length() <= ModuleEventImpactResolver.MAX_RESOLVER_KEY_LENGTH
                && RESOLVER_KEY_PATTERN.matcher(key).matches();
    }

    private static boolean positive(Long id) {
        return id != null && id > 0;
    }
}
