package com.dataanalyst;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the AI Data Analyst Agent Spring Boot application.
 *
 * <p>The application exposes a REST API that accepts natural-language questions,
 * generates safe read-only SQL via an LLM-powered agent, executes the query
 * against a MySQL database, and returns a structured AnalysisResponse containing
 * a business insight summary, chart recommendation, data table, and the generated SQL.
 */
@SpringBootApplication
@EnableScheduling
public class AiDataAnalystAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDataAnalystAgentApplication.class, args);
    }
}
