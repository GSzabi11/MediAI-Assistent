package bme.mediai.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

// @Async: a dokumentum-feltöltés a Python értesítését nem várja meg (202 Accepted)
@Configuration
@EnableAsync
public class AsyncConfig {
}
