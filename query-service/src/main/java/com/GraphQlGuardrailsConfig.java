package com;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphQlGuardrailsConfig {

    @Bean
    public Instrumentation maxDepth(@Value("${fleetmind.graphql.max-depth:15}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    @Bean
    public Instrumentation maxComplexity(@Value("${fleetmind.graphql.max-complexity:300}") int maxComplexity) {
        return new MaxQueryComplexityInstrumentation(maxComplexity);
    }
}
