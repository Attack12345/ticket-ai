package com.ticketai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketServerApplication.class, args);
    }
}
