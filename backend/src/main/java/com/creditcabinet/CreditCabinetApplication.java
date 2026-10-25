package com.creditcabinet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CreditCabinetApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditCabinetApplication.class, args);
    }
}
