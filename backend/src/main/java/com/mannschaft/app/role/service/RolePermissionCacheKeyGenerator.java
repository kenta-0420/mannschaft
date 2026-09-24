package com.mannschaft.app.role.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/** role-permissions 用の永続世代付きキャッシュキーを生成する。 */
@Component("rolePermissionCacheKeyGenerator")
@RequiredArgsConstructor
public class RolePermissionCacheKeyGenerator implements KeyGenerator {

    private final RolePermissionCacheGenerationService generationService;

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params.length != 3
                || !(params[0] instanceof Long userId)
                || !(params[1] instanceof Long scopeId)
                || !(params[2] instanceof String scopeType)) {
            throw new IllegalArgumentException("role-permissions のキャッシュキー引数が不正です");
        }
        long generation = generationService.currentGeneration(scopeType, scopeId);
        // MEMBER の管理権限3件を初期 OFF にする前のキャッシュを参照しない。
        return "v3:" + scopeType + ":" + scopeId + ":g" + generation + ":" + userId;
    }
}
