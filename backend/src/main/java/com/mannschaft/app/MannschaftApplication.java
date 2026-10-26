package com.mannschaft.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class MannschaftApplication {

	public static void main(String[] args) {
		SpringApplication.run(MannschaftApplication.class, args);
	}

}
