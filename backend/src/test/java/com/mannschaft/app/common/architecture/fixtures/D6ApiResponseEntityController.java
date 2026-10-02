package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: {@code ApiResponse<Entity>} で 1 段ラップして Entity を返す公開エンドポイント
 * （D-6 で違反として検出されるべき — ジェネリクス型引数の Entity を捕捉できるかの担保）。
 */
@RestController
@RequestMapping("/fixtures/d6/api-response-entity")
public class D6ApiResponseEntityController {

    /** ApiResponse&lt;Entity&gt; 返し（違反）。 */
    @GetMapping
    public ApiResponse<DummyD6ExposedEntity> wrapped() {
        return null;
    }
}
