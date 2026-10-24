package bme.mediai.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Async: a dokumentum-feltöltés a Python értesítését nem várja meg (202 Accepted)
// @Scheduled: a félbemaradt feldolgozás folytatása (DocumentProcessingResume)
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
