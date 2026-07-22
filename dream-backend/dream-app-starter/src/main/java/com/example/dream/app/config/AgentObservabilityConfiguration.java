package com.example.dream.app.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Restricts exported observations to an Agent execution tree.
 *
 * <p>The Agent observations become trace roots. Any Spring AI, HTTP client,
 * database or Redis observation created below them is retained through the
 * parent check, while unrelated web requests (including CORS preflight) are
 * discarded before spans are created.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "dream.observability",
        name = "agent-only",
        havingValue = "true",
        matchIfMissing = true)
public class AgentObservabilityConfiguration {

    private static final String AGENT_OBSERVATION_PREFIX = "dream.agent.";

    @Bean
    ObservationPredicate agentObservationPredicate() {
        return (name, context) -> isAgentObservation(name) || hasAgentAncestor(context);
    }

    private boolean isAgentObservation(String name) {
        return name != null && name.startsWith(AGENT_OBSERVATION_PREFIX);
    }

    private boolean hasAgentAncestor(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        while (parent != null) {
            if (isAgentObservation(parent.getContextView().getName())) {
                return true;
            }
            parent = parent.getContextView().getParentObservation();
        }
        return false;
    }
}
