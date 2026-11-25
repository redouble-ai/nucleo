/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.processor;

import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.*;
import java.util.*;

/**
 * Refuses, when the code is compiled, an {@code @LLMSummarizable} field declared in a class
 * that is no artifact.
 *
 * <p>A summarized field keeps two promises: a model is shown its summary, and the model can
 * read the whole value when it needs it. Both rest on the artifact that declares the field,
 * which carries the reference the value is read by and keeps the summary. A field declared
 * anywhere else has neither, so the declaration is an error on the line of the field. The
 * class that declares the field is what is judged: a field inherited by an artifact from a
 * class that is none is refused where it is declared.
 *
 * <p>The processor depends on nothing of the runtime. It knows the annotation and the
 * artifact interface by their names, {@link #SUMMARIZABLE} and {@link #ARTIFACT}, which a
 * test of the runtime holds equal to the names of the classes themselves.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
public class SummarizableFieldProcessor extends AbstractProcessor {
    /** The name of the annotation this processor judges. */
    public static final String SUMMARIZABLE = "ai.redouble.nucleo.harness.schema.LLMSummarizable";
    /** The name of the interface the declaring class of an annotated field implements. */
    public static final String ARTIFACT = "ai.redouble.nucleo.harness.artifacts.Artifact";

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(SUMMARIZABLE);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (TypeElement annotation : annotations) {
            TypeElement artifact = processingEnv.getElementUtils().getTypeElement(ARTIFACT);
            for (Element field : round.getElementsAnnotatedWith(annotation)) {
                if (artifact == null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@LLMSummarizable is on the classpath and " + ARTIFACT + " is not, so the field's declaring class cannot be judged", field);
                    continue;
                }
                Element declaring = field.getEnclosingElement();
                TypeMirror declaringType = processingEnv.getTypeUtils().erasure(declaring.asType());
                TypeMirror artifactType = processingEnv.getTypeUtils().erasure(artifact.asType());
                if (!processingEnv.getTypeUtils().isAssignable(declaringType, artifactType)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@LLMSummarizable is legal only on a field declared in a class that implements Artifact: "
                                    + declaring.getSimpleName() + " is none, so a model shown the summary of '" + field.getSimpleName()
                                    + "' could never read its full value. Make " + declaring.getSimpleName()
                                    + " an artifact, or remove the annotation", field);
                }
            }
        }
        // Other processors may read the annotation too
        return false;
    }
}
