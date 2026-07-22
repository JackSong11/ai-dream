package com.example.dream.app.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentObservabilityConfigurationTest {

    private final AgentObservabilityConfiguration configuration = new AgentObservabilityConfiguration();
    private final ObservationPredicate predicate = configuration.agentObservationPredicate();

    @Test
    void acceptsAgentRootsAndRejectsUnrelatedRoots() {
        assertThat(predicate.test("dream.agent.loop", new Observation.Context())).isTrue();
        assertThat(predicate.test("http.server.requests", new Observation.Context())).isFalse();
        assertThat(predicate.test("spring.ai.chat.client", new Observation.Context())).isFalse();
    }

    @Test
    void acceptsEveryObservationNestedBelowAnAgent() {
        Observation.Context agentContext = new Observation.Context();
        agentContext.setName("dream.agent.loop");
        ObservationView agent = () -> agentContext;
        Observation.Context child = new Observation.Context();
        child.setParentObservation(agent);

        assertThat(predicate.test("spring.ai.chat.client", child)).isTrue();
        assertThat(predicate.test("http.client.requests", child)).isTrue();
    }
}
