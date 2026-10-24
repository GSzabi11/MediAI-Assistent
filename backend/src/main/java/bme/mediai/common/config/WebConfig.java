package bme.mediai.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import bme.mediai.audit.api.AccessLogInterceptor;
import bme.mediai.common.api.ReadOnlyModeInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccessLogInterceptor accessLogInterceptor;
    private final ReadOnlyModeInterceptor readOnlyModeInterceptor;

    public WebConfig(AccessLogInterceptor accessLogInterceptor, ReadOnlyModeInterceptor readOnlyModeInterceptor) {
        this.accessLogInterceptor = accessLogInterceptor;
        this.readOnlyModeInterceptor = readOnlyModeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessLogInterceptor).addPathPatterns("/api/**");
        // a napló után: az elutasított (403) író kísérlet is naplózódik
        registry.addInterceptor(readOnlyModeInterceptor).addPathPatterns("/api/**");
    }
}
