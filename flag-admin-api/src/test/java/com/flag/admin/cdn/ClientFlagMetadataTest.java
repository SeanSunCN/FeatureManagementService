package com.flag.admin.cdn;

import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientFlagMetadata} conversion logic.
 */
class ClientFlagMetadataTest {

    @Test
    @DisplayName("converts FlagConfig with rules to ClientFlagMetadata")
    void convertsWithRules() {
        FlagConfig config = FlagConfig.builder()
                .flagKey("new-ui-portal")
                .globalEnabled(true)
                .defaultServeValue(false)
                .rules(List.of(
                        EvaluationRule.builder()
                                .ruleName("US users")
                                .serveValue(true)
                                .conditions(List.of(
                                        Condition.builder()
                                                .attribute("country")
                                                .operator(Condition.Operator.IN)
                                                .values(List.of("US"))
                                                .build()
                                ))
                                .build()
                ))
                .build();

        ClientFlagMetadata result = ClientFlagMetadata.from(config);

        assertThat(result.flagKey()).isEqualTo("new-ui-portal");
        assertThat(result.enabled()).isTrue();
        assertThat(result.defaultServeValue()).isFalse();
        assertThat(result.rules()).hasSize(1);

        Map<String, Object> rule = result.rules().get(0);
        assertThat(rule).containsEntry("ruleName", "US users");
        assertThat(rule).containsEntry("serveValue", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) rule.get("conditions");
        assertThat(conditions).hasSize(1);
        assertThat(conditions.get(0))
                .containsEntry("attribute", "country")
                .containsEntry("operator", "IN");
    }

    @Test
    @DisplayName("converts FlagConfig with null rules to empty list")
    void convertsNullRules() {
        FlagConfig config = FlagConfig.builder()
                .flagKey("dark-mode")
                .globalEnabled(true)
                .defaultServeValue(true)
                .build();

        ClientFlagMetadata result = ClientFlagMetadata.from(config);

        assertThat(result.rules()).isEmpty();
    }
}