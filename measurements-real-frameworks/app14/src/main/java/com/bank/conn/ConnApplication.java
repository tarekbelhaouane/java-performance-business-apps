package com.bank.conn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ConnApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConnApplication.class, args);
    }
}
