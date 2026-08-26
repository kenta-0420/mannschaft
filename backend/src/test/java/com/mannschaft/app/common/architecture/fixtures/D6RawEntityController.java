package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: 素の JPA Entity をそのまま返す公開エンドポイント（D-6 で違反として検出されるべき）。
 */
@RestController
@RequestMapping("/fixtures/d6/raw-entity")
public class D6RawEntityController {

    /** 素の Entity 返し（違反）。 */
    @GetMapping
    public DummyD6ExposedEntity raw() {
        return null;
    }
}
