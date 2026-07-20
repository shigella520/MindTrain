package io.github.shigella520.mindtrain.core;

import io.github.shigella520.mindtrain.core.config.MindTrainProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MindTrainProperties.class)
public class MindTrainCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(MindTrainCoreApplication.class, args);
    }
}
