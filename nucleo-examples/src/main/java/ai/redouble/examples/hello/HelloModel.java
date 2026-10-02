/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.hello;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.builtin.*;

/**
 * The smallest Nucleo program: ask a model one question about a piece of text and print the
 * answer. It shows the three things every later example builds on.
 *
 * <p>Everything Nucleo carries out is a job, and {@link JobDispatcher} is the runtime that
 * runs jobs: {@code submit} returns a handle at once, the job runs on a virtual thread of
 * its own, and {@code get()} waits for the result. {@code Job.workflow("you", "hello")}
 * names the piece of work this job belongs to; costs, events and records are kept per
 * workflow.
 *
 * <p>No model is named anywhere. {@code Grade.SMALL} says how much model the work needs, a
 * rung on a ladder from MICRO to MEGA, and the deployment's catalog decides which model
 * serves that rung, so the same program runs on Anthropic, OpenAI or Bedrock, served by
 * whichever holds a credential in the environment.
 *
 * <p>The answer arrives as a {@code QuickLLMQuestionOutput}, a plain Java object already
 * read and checked against its declared fields: values to use, never text to parse.
 *
 * <p>Change the question and the text to anything; nothing else in the program cares what
 * they say.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class HelloModel {
    public static void main(String[] args) throws Exception {
        // region hello
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            QuickLLMQuestionInput question = new QuickLLMQuestionInput();
            question.setQuestion("Who is speaking, and where are they going?");
            question.setContext("Call me Ishmael. Some years ago, having little money in my purse,"
                    + " I thought I would sail about a little and see the watery part of the world.");
            question.setGrade(Grade.SMALL);
            QuickLLMQuestionTool tool = new QuickLLMQuestionTool(Job.workflow("you", "hello"));
            tool.setInput(question);
            QuickLLMQuestionOutput answer = dispatcher.submit(tool).get();
            System.out.println(answer.getAnswer());
            System.out.println("confidence: " + answer.getConfidence());
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
