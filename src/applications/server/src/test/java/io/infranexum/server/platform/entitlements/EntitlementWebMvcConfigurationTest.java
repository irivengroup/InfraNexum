package io.infranexum.server.platform.entitlements;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class EntitlementWebMvcConfigurationTest {
    @Test
    void registersTheMutationGuardForEveryApiPath() {
        var interceptor = mock(EntitlementMutationInterceptor.class);
        var registry = mock(InterceptorRegistry.class);
        var registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(interceptor)).thenReturn(registration);
        new EntitlementWebMvcConfiguration(interceptor).addInterceptors(registry);
        verify(registration).addPathPatterns("/api/**");
    }
}
