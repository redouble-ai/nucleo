/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.secrets.*;
import org.springframework.context.*;
import org.springframework.core.env.*;

import java.util.function.*;

/**
 * The runtime's credentials, bound the way every other Boot property is. A consumer asks the
 * runtime for the name it needs, {@code anthropic-api-key}; this store answers from the
 * property {@code nucleo.credentials.anthropic-api-key}, with {@code .user} and {@code .host}
 * for a credential's other parts. What binds that property is the deployment's business and
 * Spring's machinery: {@code application.yaml} binds it from an environment variable, a
 * profile, a vault or a Kubernetes secret, the same way it binds
 * {@code spring.datasource.password}.
 *
 * <p>A part no property binds is read from the environment variable of the same name under
 * the runtime's one rule ({@link EnvironmentSecrets}: {@code ANTHROPIC_API_KEY}, and
 * {@code _USER} / {@code _HOST} for the other parts), part by part, so an application that
 * binds nothing still runs with the variables exported, and a host bound in the file and a
 * secret exported in the environment compose into one credential. The property wins where
 * both are set, so a deployment that binds a part deliberately is never overridden by a
 * stray variable.
 *
 * <p>The runtime builds its store by reflection, before or after the context exists, so the
 * Spring {@link Environment} reaches the instances through the bean this class also is: the
 * auto-configuration declares it, the context sets the environment once, and a store asked
 * before then refuses rather than answering from nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class SpringSecrets implements Secrets, EnvironmentAware {
    /** The property namespace the runtime owns in a Boot application, the way Spring AI owns {@code spring.ai.*}. */
    public static final String PREFIX = "nucleo.credentials.";
    private static volatile Environment environment;
    private final EnvironmentSecrets variables;

    public SpringSecrets() {
        this(System::getenv);
    }

    /** Over an arbitrary variable source for the environment fallback, so the composition is testable without touching the process environment. */
    public SpringSecrets(Function<String, String> variables) {
        this.variables = new EnvironmentSecrets(variables);
    }

    @Override
    public void setEnvironment(Environment environment) {
        SpringSecrets.environment = environment;
    }

    /** The credential's parts by its shape: the Boot properties to bind, or the environment variables to export. */
    @Override
    public String describe(String id) {
        CredentialShape shape = CredentialShapes.of(id);
        return shape.describeProperties(PREFIX) + " bound in application.yaml the way any Boot property is (a profile, a vault, a"
                + " Kubernetes secret), or " + shape.describeVariables();
    }

    @Override
    public Credential find(String id) {
        Environment env = environment;
        if (env == null) {
            throw new IllegalStateException("The Spring environment has not reached " + SpringSecrets.class.getSimpleName()
                    + " yet; the runtime asked for " + id + " before the application context was built");
        }
        Credential exported = variables.find(id);
        String secret = part(value(env, PREFIX + id), exported != null ? exported.secret() : null);
        String user = part(value(env, PREFIX + id + ".user"), exported != null ? exported.user() : null);
        String host = part(value(env, PREFIX + id + ".host"), exported != null ? exported.host() : null);
        if (secret == null && user == null && host == null) {
            return null;
        }
        return new Credential(user, secret, host);
    }

    /** The bound value where there is one, else what the environment exports for that part. */
    private static String part(String bound, String exported) {
        return bound != null ? bound : exported;
    }

    private static String value(Environment env, String property) {
        String v = env.getProperty(property);
        return v == null || v.isEmpty() ? null : v;
    }
}
