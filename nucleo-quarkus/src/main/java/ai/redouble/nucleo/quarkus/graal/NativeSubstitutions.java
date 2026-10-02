/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus.graal;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import org.apache.hc.client5.http.entity.DeflateInputStreamFactory;
import org.apache.hc.client5.http.entity.GZIPInputStreamFactory;
import org.apache.hc.client5.http.entity.InputStreamFactory;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.MessageSupport;

import java.util.Arrays;
import java.util.List;

/**
 * Native-image substitutions that keep two inert optional-integration paths of libraries the runtime
 * uses from pulling optional dependencies, deliberately absent here, into the image link.
 *
 * <p>Apache HttpClient's {@code ContentCompressionExec} registers a Brotli decoder in its
 * decoder-registry constructor, referencing {@code BrotliInputStreamFactory}, whose body needs the
 * {@code org.brotli:dec} jar. Brotli is registered only when {@code BrotliDecompressingEntity}
 * reports the jar present, so with the jar absent the decoder is never used; but the whole-application
 * {@code --link-at-build-time} still resolves the reference in the constructor's bytecode, and the
 * factory class fails to load. Neither deleting nor substituting the factory helps, because the
 * builder verifies it while resolving the call. The load-safe cut is to re-declare the one
 * constructor that names it, building the gzip/deflate registry the runtime always gets and omitting
 * the brotli entry it never gets. This mirrors {@code ContentCompressionExec}'s own logic for the
 * pinned httpclient5 version.
 *
 * <p>org.reflections' {@code JbossDir} is a VFS handler for JBoss/WildFly URLs that carries
 * {@code org.jboss.vfs.VirtualFile} in its fields and signatures and matches no URL outside an
 * application server. It loads without its absent dependency, so the whole class is deleted.
 *
 * <p>This is native behavior owned by the extension for libraries the runtime already uses. It is
 * not hand-rolled native support for a dependency: no jars are added and no reflection or resource
 * metadata is written. A dependency the native image needs is carried by its own Quarkus or
 * Quarkiverse extension, or kept out of the native build behind a maven profile.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-23)
 */
final class NativeSubstitutions {
    private NativeSubstitutions() {}
}

@TargetClass(className = "org.apache.hc.client5.http.impl.classic.ContentCompressionExec")
final class Target_org_apache_hc_client5_http_impl_classic_ContentCompressionExec {
    @Alias
    Header acceptEncoding;
    @Alias
    Lookup<InputStreamFactory> decoderRegistry;
    @Alias
    boolean ignoreUnknown;
    @Alias
    int maxCodecListLen;

    @Substitute
    Target_org_apache_hc_client5_http_impl_classic_ContentCompressionExec(
            List<String> acceptEncoding, Lookup<InputStreamFactory> decoderRegistry,
            boolean ignoreUnknown, int maxCodecListLen) {
        this.acceptEncoding = MessageSupport.headerOfTokens("Accept-Encoding",
                acceptEncoding != null ? acceptEncoding : Arrays.asList("gzip", "x-gzip", "deflate"));
        this.decoderRegistry = decoderRegistry != null ? decoderRegistry
                : RegistryBuilder.<InputStreamFactory>create()
                        .register("gzip", GZIPInputStreamFactory.getInstance())
                        .register("x-gzip", GZIPInputStreamFactory.getInstance())
                        .register("deflate", DeflateInputStreamFactory.getInstance())
                        .build();
        this.ignoreUnknown = ignoreUnknown;
        this.maxCodecListLen = maxCodecListLen;
    }
}

@Delete
@TargetClass(className = "org.reflections.vfs.JbossDir")
final class Target_org_reflections_vfs_JbossDir {
}
