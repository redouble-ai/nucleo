/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import ai.redouble.demo.decide.*;
import io.quarkus.test.junit.*;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

/**
 * The decision endpoints on the Quarkus host, the same routes the Spring host serves:
 * {@code GET /decide} is the decision agent's objective and palette, {@code POST /decide}
 * refuses a run without a folder, and {@code POST /decide-prices} refuses before any
 * extraction, naming the step.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
@QuarkusTest
class DecisionEndpointsTest {

    @Test
    void theDecisionAgentReportsItsPalette() {
        given().when().get("/decide")
                .then().statusCode(200)
                .body("objective", equalTo(PriceChangeFinder.OBJECTIVE))
                .body("takes", equalTo("folder"))
                .body("answers", equalTo("statement"))
                .body("tools[0].name", equalTo("list_folder"))
                .body("tools[2].name", equalTo("split_statements"));
    }

    @Test
    void aRunWithoutAFolderIsRefusedWithTheRuleUnderMessage() {
        given().contentType("application/json").body("{\"directory\":\" \"}")
                .when().post("/decide")
                .then().statusCode(400)
                .body("message", containsString("folder"));
    }

    @Test
    void decidedPricingBeforeAnyExtractionIsRefusedWithTheStepUnderMessage() {
        given().contentType("application/json").body("{}")
                .when().post("/decide-prices")
                .then().statusCode(409)
                .body("message", containsString("/extract"));
    }
}
