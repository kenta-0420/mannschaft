package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: メソッド本体で <b>直接</b> ダミー認可クラス（{@link DummyAccessGuard}）を
 * 呼ぶ Controller。深さ0（直接呼び）で認可シグナルが成立するため
 * <b>賢化前・賢化後のどちらでも合格</b>すべきケース。
 */
@RestController
@RequestMapping("/fixtures/authorized-direct")
public class AuthorizedDirectController {

    private final DummyAccessGuard accessGuard = new DummyAccessGuard();

    /** 直接 AccessGuard を呼ぶ公開エンドポイント（合格すべき）。 */
    @GetMapping
    public String directCall(Long scopeId) {
        accessGuard.checkAccess(scopeId);
        return "ok";
    }
}
