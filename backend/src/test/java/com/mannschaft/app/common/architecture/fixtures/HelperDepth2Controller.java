package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: 認可を委譲した薄い Controller。
 *
 * <p>委譲経路: {@code viaService}(深さ0) → {@link DummyDelegateService#doWork}(深さ1) →
 * 同サービス内 private helper(深さ2) → {@link DummyAccessGuard}。
 *
 * <p><b>賢化前（直接呼びのみ判定）</b>: 直接は認可クラスを呼ばないため「シグナルなし」＝
 * unauthorized と同じ扱いになり <b>検出漏れ</b>（＝この番人が救えていない負債の正体）。
 * <b>賢化後（D=2 BFS）</b>: 深さ2で認可クラスに到達するため <b>合格</b>すべき。
 * メタテストではこの red→green の遷移を assert する。
 */
@RestController
@RequestMapping("/fixtures/helper-depth2")
public class HelperDepth2Controller {

    private final DummyDelegateService delegateService = new DummyDelegateService();

    /** 認可を Service に委譲する公開エンドポイント（D=2 で合格すべき）。 */
    @GetMapping
    public String viaService(Long scopeId) {
        delegateService.doWork(scopeId);
        return "ok";
    }
}
