package com.gigtasker.bidservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BidServiceApplication {

    private BidServiceApplication() {}

	static void main(String[] args) {
		SpringApplication.run(BidServiceApplication.class, args);
	}

}
