package com.bank.bench;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class NorvaneBenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(NorvaneBenchApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "bench.create-topic", havingValue = "true", matchIfMissing = true)
    NewTopic paymentEvents() {
        return TopicBuilder.name("payment-events").partitions(12).replicas(1).build();
    }
}
