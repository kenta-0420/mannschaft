package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: DTO を（素・多段ラップとも）返す公開エンドポイント。Entity を一切露出しないため
 * D-6 番人は違反として検出<b>してはならない</b>（＝合格すべき対照ケース）。
 *
 * <p>{@link D6NestedEntityController} と同じ 3 段入れ子ラッパーでも、最深の型引数が
 * {@code @Entity} でない DTO（{@link DummyD6ResponseDto}）であれば合格することで、
 * 「ラッパーの深さ」ではなく「{@code @Entity} の有無」で判定していることを担保する。
 */
@RestController
@RequestMapping("/fixtures/d6/dto")
public class D6DtoController {

    /** 素の DTO 返し（合格）。 */
    @GetMapping("/plain")
    public DummyD6ResponseDto plain() {
        return null;
    }

    /** 3 段入れ子だが最深が DTO のため合格。 */
    @GetMapping("/nested")
    public ResponseEntity<ApiResponse<Page<DummyD6ResponseDto>>> nestedDto() {
        return null;
    }
}
