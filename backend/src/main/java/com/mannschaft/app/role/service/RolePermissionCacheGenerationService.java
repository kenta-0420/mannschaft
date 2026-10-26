package com.mannschaft.app.role.service;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.role.repository.RolePermissionCacheGenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** role-permissions キャッシュキーに使うスコープ単位の世代番号を扱う。 */
@Service
@RequiredArgsConstructor
public class RolePermissionCacheGenerationService {

    private final RolePermissionCacheGenerationRepository repository;

    /** 行がまだ無いスコープは初期世代0とみなす。 */
    @Transactional(readOnly = true)
    public long currentGeneration(String scopeType, Long scopeId) {
        return repository.findGeneration(scopeType, scopeId).orElse(0L);
    }

    /** 権限変更と同じトランザクション内で世代を原子的に進める。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long incrementGeneration(String scopeType, Long scopeId) {
        repository.increment(UuidV7.generate(), scopeType, scopeId);
        return repository.findGeneration(scopeType, scopeId)
                .orElseThrow(() -> new IllegalStateException("認可キャッシュ世代の更新後行が見つかりません"));
    }
}
