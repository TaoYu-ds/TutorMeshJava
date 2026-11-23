package com.tutormesh.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TutorMeshProperties.class)
public class AppConfig {
}
