package io.infranexum.server.platform.entitlements;

import java.util.Objects;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the global entitlement guard at the HTTP application boundary. */
public final class EntitlementWebMvcConfiguration implements WebMvcConfigurer {
    private final EntitlementMutationInterceptor interceptor;

    public EntitlementWebMvcConfiguration(EntitlementMutationInterceptor interceptor) {
        this.interceptor = Objects.requireNonNull(interceptor, "interceptor");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
