package cn.jianda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JiandaApplication {
    public static void main(String[] args) {
        SpringApplication.run(JiandaApplication.class, args);
    }
}

