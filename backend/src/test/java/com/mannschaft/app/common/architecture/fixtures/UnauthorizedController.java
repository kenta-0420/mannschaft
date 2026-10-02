package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: 認可呼び出しが一切ない公開エンドポイントを持つ Controller。
 * {@code @PreAuthorize} も無く、委譲先も認可クラスに到達しない。
 * <b>賢化前・賢化後のどちらでも「認可シグナルなし」＝違反として検出</b>されるべき。
 * この検出が効くことが偽陰性ゼロの肝（緩めすぎ検知）。
 */
@RestController
@RequestMapping("/fixtures/unauthorized")
public class UnauthorizedController {

    private final DummyPlainService plainService = new DummyPlainService();

    /** 認可を一切行わない公開エンドポイント（違反として検出されるべき）。 */
    @GetMapping
    public String noAuth(Long id) {
        return plainService.loadData(id);
    }
}
