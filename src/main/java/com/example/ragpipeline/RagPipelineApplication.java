package com.example.ragpipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagPipelineApplication {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RagPipelineApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupInfo() {
        return args -> {
            log.info("=================================================================");
            log.info("🚀 Spring AI RAG Pipeline started successfully on Java {}", Runtime.version());
            log.info("📂 UI Dashboard: http://localhost:8080");
            log.info("📖 API Endpoints: http://localhost:8080/api/rag/status");
            log.info("=================================================================");
        };
    }
}
