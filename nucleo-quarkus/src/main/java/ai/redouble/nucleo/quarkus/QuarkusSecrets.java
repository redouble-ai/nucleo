/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus;

import ai.redouble.nucleo.secrets.*;
import org.eclipse.microprofile.config.*;

import java.util.function.*;

/**
 * The runtime's credentials, read as MicroProfile Config properties. A consumer asks the
 * runtime for the name it needs, {@code anthropic-api-key}; this store answers from the
 * property {@code nucleo.credentials.anthropic-api-key}, with {@code .user} and {@code .host}
 * for a credential's other parts. What binds that property is the deployment's business and
 * Quarkus's config machinery: {@code application.properties} binds it from an environment
 * variable, a system property, a profile or a config source of the deployment's own, the same
 * way it binds {@code quarkus.datasource.password}.
 *
 * <p>A part no property binds is read from the environment variable of the same name under
 * the runtime's one rule ({@link EnvironmentSecrets}: {@code ANTHROPIC_API_KEY}, and
 * {@code _USER} / {@code _HOST} for the other parts), part by part, so an application that
 * binds nothing still runs with the variables exported. The property wins where both are set.
 *
 * <p>The runtime builds its store by reflection through a no-argument constructor, so this
 * store reaches configuration the way any code does, through {@link ConfigProvider}; there is
 * no container reference to hold. A part left unset in every config source and in the
 * environment is absent, and a credential with no part at all is null, which the runtime
 * reports as "not configured".
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class QuarkusSecrets implements Secrets {
    /** The property namespace the runtime owns in a Quarkus application, the way Quarkus owns {@code quarkus.*}. */
    public static final String PREFIX = "nucleo.credentials.";
    private final EnvironmentSecrets variables;

    public QuarkusSecrets() {
        this(System::getenv);
    }

    /** Over an arbitrary variable source for the environment fallback, so the composition is testable without touching the process environment. */
    public QuarkusSecrets(Function<String, String> variables) {
        this.variables = new EnvironmentSecrets(variables);
    }

    /** The credential's parts by its shape: the MicroProfile Config properties to bind, or the environment variables to export. */
    @Override
    public String describe(String id) {
        CredentialShape shape = CredentialShapes.of(id);
        return shape.describeProperties(PREFIX) + " bound in application.properties the way any MicroProfile Config property is (a"
                + " system property, a profile, a config source), or " + shape.describeVariables();
    }

    @Override
    public Credential find(String id) {
        Credential exported = variables.find(id);
        String secret = part(value(PREFIX + id), exported != null ? exported.secret() : null);
        String user = part(value(PREFIX + id + ".user"), exported != null ? exported.user() : null);
        String host = part(value(PREFIX + id + ".host"), exported != null ? exported.host() : null);
        if (secret == null && user == null && host == null) {
            return null;
        }
        return new Credential(user, secret, host);
    }

    /** The bound value where there is one, else what the environment exports for that part. */
    private static String part(String bound, String exported) {
        return bound != null ? bound : exported;
    }

    private static String value(String property) {
        String v = ConfigProvider.getConfig().getOptionalValue(property, String.class).orElse(null);
        return v == null || v.isEmpty() ? null : v;
    }
}
