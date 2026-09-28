package com.yufeichi.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class YufeichiServerApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--bootstrap")) {
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(
                    com.yufeichi.server.config.BootstrapConfiguration.class)
                    .profiles("prod", "bootstrap").web(org.springframework.boot.WebApplicationType.NONE)
                    .run(java.util.stream.Stream.concat(java.util.Arrays.stream(args)
                            .filter(arg -> !arg.equals("--bootstrap") && !arg.startsWith("--spring.profiles.active=")),
                            java.util.stream.Stream.of("--spring.profiles.active=prod,bootstrap")).toArray(String[]::new))) {
                // ApplicationRunner finishes the transaction before the offline context closes.
            }
            return;
        }
        SpringApplication.run(YufeichiServerApplication.class, args);
    }

}
