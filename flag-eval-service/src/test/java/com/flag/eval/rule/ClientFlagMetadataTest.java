package com.flag.eval.rule;

import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClientFlagMetadata} DTO conversion.
 */
class ClientFlagMetadataTest {

    @Test
    @DisplayName("from() converts FlagConfig with no rules")
    void fromFlagConfigNoRules() {
        FlagConfig config = FlagConfig.builder()
                .flagKey("new-ui-portal")
                .globalEnabled(true)
                .defaultServeValue(true)
                .rules(List.of())
                .build();

        ClientFlagMetadata meta = ClientFlagMetadata.from(config);

        assertThat(meta.flagKey()).isEqualTo("new-ui-portal");
        assertThat(meta.enabled()).isTrue();
        assertThat(meta.defaultServeValue()).isTrue();
        assertThat(meta.rules()).isEmpty();
    }

    @Test
    @DisplayName("from() converts FlagConfig with rules and conditions")
    void fromFlagConfigWithRules() {
        FlagConfig config = FlagConfig.builder()
                .flagKey("dark-mode-v2")
                .globalEnabled(true)
                .defaultServeValue(false)
                .rules(List.of(
                        EvaluationRule.builder()
                                .ruleId("rule-1")
                                .ruleName("beta-users")
                                .serveValue(true)
                                .conditions(List.of(
                                        Condition.builder()
                                                .attribute("userId")
                                                .operator(Condition.Operator.IN)
                                                .values(List.of("user-a", "user-b"))
                                                .build()
                                ))
                                .build()
                ))
                .build();

        ClientFlagMetadata meta = ClientFlagMetadata.from(config);

        assertThat(meta.flagKey()).isEqualTo("dark-mode-v2");
        assertThat(meta.enabled()).isTrue();
        assertThat(meta.defaultServeValue()).isFalse();
        assertThat(meta.rules()).hasSize(1);

        Map<String, Object> rule = meta.rules().get(0);
        assertThat(rule).containsEntry("ruleName", "beta-users");
        assertThat(rule).containsEntry("serveValue", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) rule.get("conditions");
        assertThat(conditions).hasSize(1);
        assertThat(conditions.get(0))
                .containsEntry("attribute", "userId")
                .containsEntry("operator", "IN");
    }

    @Test
    @DisplayName("from() handles null rules gracefully")
    void fromFlagConfigNullRules() {
        FlagConfig config = FlagConfig.builder()
                .flagKey("feature-x")
                .globalEnabled(false)
                .defaultServeValue(false)
                .build();
        // rules not set = null

        ClientFlagMetadata meta = ClientFlagMetadata.from(config);

        assertThat(meta.flagKey()).isEqualTo("feature-x");
        assertThat(meta.enabled()).isFalse();
        assertThat(meta.rules()).isEmpty();
    }
}
