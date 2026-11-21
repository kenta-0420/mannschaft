package com.mannschaft.app.signage.service;

import com.mannschaft.app.signage.service.SignageAccessTokenService.IssueSignageTokenRequest;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 実発行DTOを生成resolverに通し、名称必須と既存の文字数制約を公開schemaにも固定する。 */
class SignageTokenRequestSchemaTest {

    @Test
    void 発行名だけが必須で文字列100文字上限を公開する() {
        var schemas = ModelConverters.getInstance().readAll(IssueSignageTokenRequest.class);
        Schema<?> request = schemas.get("IssueSignageTokenRequest");
        assertThat(request).isNotNull();
        Schema<?> name = (Schema<?>) request.getProperties().get("name");
        assertThat(name).isNotNull();
        assertThat(name.getType()).isEqualTo("string");
        assertThat(name.getMaxLength()).isEqualTo(100);
        assertThat(name.getMinLength()).isEqualTo(1);
        assertThat(request.getRequired()).containsExactly("name");
    }
}
