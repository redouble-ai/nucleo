/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

/**
 * What the starter puts into a Boot application: the credential store that reads
 * {@code nucleo.credentials.*}, the binder that maps {@code nucleo.*} properties onto the
 * registered {@code Settings} classes, the runtime's lifecycle bean, and the registration of
 * the application's database. Each bean steps
 * aside for one the host declares itself, which is how a host subscribes its own observers
 * to the dispatcher or answers credentials from somewhere Spring's environment does not
 * reach.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
@AutoConfiguration
public class NucleoAutoConfiguration {

    /**
     * Binds {@code nucleo.*} properties onto the registered settings classes before any bean
     * builds, so a bean touching the runtime during its own construction already sees the
     * bound values. Static: a BeanFactoryPostProcessor bean must not force its declaring
     * configuration to instantiate early.
     */
    @Bean
    static BeanFactoryPostProcessor nucleoSettingsBinder(Environment environment) {
        return beanFactory -> SpringSettingsBinder.bind(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSecrets nucleoSecrets() {
        return new SpringSecrets();
    }

    @Bean
    @ConditionalOnMissingBean
    public NucleoRuntime nucleoRuntime(ApplicationArguments arguments) {
        return new NucleoRuntime(arguments);
    }

    /**
     * The application's database: a counting provider registered with the runtime when
     * {@code nucleo.database.max-concurrent} is set.
     */
    @Bean
    @ConditionalOnMissingBean
    public NucleoDatabase nucleoDatabase() {
        return new NucleoDatabase();
    }
}
