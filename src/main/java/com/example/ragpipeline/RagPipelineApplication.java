package com.example.ragpipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagPipelineApplication {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RagPipelineApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupInfo(Environment env) {
        return args -> {
            String port = env.getProperty("server.port", "8080");
            log.info("=================================================================");
            log.info("🚀 Spring AI RAG Pipeline started successfully on Java {}", Runtime.version());
            log.info("📂 UI Dashboard: http://localhost:{}", port);
            log.info("📖 API Endpoints: http://localhost:{}/api/rag/status", port);
            log.info("=================================================================");
        };
    }
}
