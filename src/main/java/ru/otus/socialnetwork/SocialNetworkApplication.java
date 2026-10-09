package ru.otus.socialnetwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SocialNetworkApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocialNetworkApplication.class, args);
    }
}
