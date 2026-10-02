# Package: ai.redouble.nucleo.tools.builtin

Some tools every agent ends up needing: the current time, because a model's sense of
"today" is whatever its training left it; date arithmetic, because models fumble exact
calculation; fetching a web page; asking a model one quick question without building an
agent for it. This package ships them, ready to put on any agent's palette. Nothing here is
offered to an agent automatically: a thinker names what it wants in
`declareDefaultTools()`, the way [Your first agent](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
names `OrderStatusTool`, or a host pushes providers through `ToolHub`.

## Catalog

| Tool name | Class | Resources | Timeout | What it does |
|-----------|-------|-----------|---------|--------------|
| `get_current_time` | `CurrentTimeTool` | none | 1s | The current instant in ISO (system zone, offset included), epoch millis, human-readable and timezone forms; every field names the same calendar date |
| `calculate_dates` | `DateCalculatorTool` | none | 5s | Signed day/month difference between two dates, optional within-N-months judgment |
| `calculate_duration` | `DurationCalculatorTool` | none | 5s | Hours and minutes between two date-times, judged against a minimum |
| `calculate_unit_rate` | `UnitRateCalculatorTool` | none | 5s | Rate times quantity with an optional cap, disclosed in the formula |
| `quick_llm_question` | `QuickLLMQuestionTool` | one LLM call | 6min | A focused question about supplied text, at the grade the asker names in the input |
| `summarize_text` | `SummarizationTool` | LLM calls | 6min | Summary at a `SummarySize`, hierarchical chunking for text of any length |
| `analyze_violation` | `AnalyzeViolationTool` | one LLM call | 6min | Judges whether a guardrail violation was the thinker's mistake or a hijack attempt |
| `web_fetch` | `WebFetchTool` | HTTP client | 30s | Fetches a page and reduces it to readable text as a `WebPageArtifact` |

`LLMSummarizer` is in this package but is not a tool: it is the `Summarizer` implementation
behind `@LLMSummarizable` fields. A static summary from the annotation wins without any
model call; content marked `llmSafe = false` or past the 200K-char ceiling truncates instead
of reaching a model; otherwise it summarizes through `summarize_text` as a job and falls
back to truncation when that job fails. Every result carries the
`[SUMMARY: N chars]` prefix with the ORIGINAL length.

---

## The calculators

LLMs are unreliable at exact arithmetic. They can reason about which numbers to multiply,
but will fumble the actual multiplication. The calculators provide deterministic,
verifiable math the LLM calls instead of attempting it in-context, and each returns a
human-readable formula or explanation so the model (and a human reviewing the output) can
verify the math. They hold no resources, are read-only, and an unparseable date or a
negative rate surfaces as `InvalidInputException` - correctable, so the model fixes the
format and retries.

### calculate_dates - DateCalculatorTool

Compares two dates and calculates the difference in days and months. Differences are
signed from dateB to dateA: positive means dateA is after dateB. The within-months
judgment requires dateA AFTER dateB and within N months.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| **Input** | | | |
| dateA | String (YYYY-MM-DD) | Yes | First date |
| dateB | String (YYYY-MM-DD) | Yes | Second date |
| withinMonths | Integer | No | Check if dateA is within N months after dateB |
| **Output** | | | |
| daysDifference | Long | | Signed days from dateB to dateA |
| monthsDifference | Long | | Signed months from dateB to dateA |
| isWithinMonths | Boolean | | Only set if withinMonths was provided |
| explanation | String | | Natural language explanation |

**Example:**
```
Input:  dateA=2025-10-15, dateB=2025-01-01, withinMonths=12
Output: daysDifference=287, monthsDifference=9, isWithinMonths=true
        explanation="dateA (2025-10-15) is 9 months and 14 days after dateB (2025-01-01). This IS within 12 months."
```

### calculate_duration - DurationCalculatorTool

Calculates hours and minutes between two date-times, with the hours rounded down, and
judges the duration against a minimum (default 20 hours when none is given).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| **Input** | | | |
| startDateTime | String (YYYY-MM-DD HH:mm) | Yes | Start timestamp |
| endDateTime | String (YYYY-MM-DD HH:mm) | Yes | End timestamp |
| minimumHours | Integer | No | Minimum hours threshold (default 20) |
| **Output** | | | |
| totalHours | Integer | | Hours between start and end (rounded down) |
| totalMinutes | Integer | | Total minutes between start and end |
| meetsMinimum | Boolean | | Whether totalHours >= minimumHours |
| minimumRequired | Integer | | The threshold that was checked |
| explanation | String | | Natural language explanation |

**Example:**
```
Input:  startDateTime=2025-10-20 14:30, endDateTime=2025-10-21 10:00, minimumHours=20
Output: totalHours=19, totalMinutes=1170, meetsMinimum=false, minimumRequired=20
        explanation="Start: 2025-10-20 14:30 -> End: 2025-10-21 10:00 = 19 hours and 30 minutes. DOES NOT MEET minimum of 20 hours."
```

### calculate_unit_rate - UnitRateCalculatorTool

Multiplies a unit rate by a quantity with an optional quantity cap. When the cap wins,
the formula discloses it, so the model cannot misreport an uncapped total.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| **Input** | | | |
| unitRate | Long | Yes | Rate per unit, non-negative |
| quantity | Integer | Yes | Number of units, non-negative |
| maxQuantity | Integer | No | Cap on quantity (null = no cap) |
| **Output** | | | |
| total | Long | | unitRate * appliedQuantity |
| appliedQuantity | Integer | | Quantity after cap |
| formula | String | | Math formula for verification |

**Example (with cap):**
```
Input:  unitRate=10000, quantity=7, maxQuantity=5
Output: total=50000, appliedQuantity=5, formula="10000 * 5 = 50000 (capped from 7 to 5)"
```

---

## The LLM utilities

### quick_llm_question - QuickLLMQuestionTool

One focused question about supplied text, answered in one model exchange with a typed
`QuickLLMQuestionOutput` (answer, confidence, reasoning). The grade travels IN THE INPUT,
per question: this tool is how anyone - a thinker's model, a doer, a servlet - asks one
question without paying for a thinker, and no fixed grade could serve every asker. A code
caller that builds the input by hand and leaves the grade unset is refused at requirements
time, because only the asker knows how much model its question deserves.

### summarize_text - SummarizationTool

Summarizes text to a target `SummarySize` (`BRIEF` / `SHORT` / `PARAGRAPHS`). Text that
fits the model's context is summarized directly; larger text is chunked on paragraph, then
sentence, then word boundaries, each chunk summarized at `SHORT`, and the combined
summaries re-summarized at the requested size. The size reaches the prompt as a prose
phrase with no digits - a precise count in the prompt is what triggers small-model
scratchpad spirals on length verification - and the numeric bound rides `max_tokens` as a
per-size backstop (768 / 1536 / 3072) that exists to stop runaway output, not to shape it.
Runs at `Grade.SMALL` as a `UTILITY` job.

### analyze_violation - AnalyzeViolationTool

Security monitoring: given the user's original input and a guardrail violation message, a
`Grade.SMALL` model judges whether the violation looks like the thinker's own reasoning
error or a manipulation attempt, with confidence and reasoning
(`ViolationAnalysisOutput`). Results feed security monitoring and incident response.

Both one-call tools are built on `AbstractModelDependentTool`: the conversation is wired
at requirements time, the exchange runs under the shared `ResponseCorrection` budget, and
a malformed answer goes back to the model as a correction turn before it ever surfaces as
a failure.

---

## The web fetcher

### web_fetch - WebFetchTool

Fetches a URL over the framework HTTP client, parses the HTML with Jsoup, strips scripts,
styles and navigation, and returns the readable text as a `WebPageArtifact` (title,
description, content, source). Content past `maxLength` (default 50,000 chars) is cut and
the cut is stated in the text, so a truncated page is never mistaken for a short one.
Upstream statuses map to the framework error types: 400/422 `InvalidInputException`,
401/403 `UnauthorizedException`, 404 `ResourceNotFoundException`, anything else
`ExternalServiceException`.

The tool carries its own address policy. It declares
`ai.redouble.nucleo.tools.guardrails.UrlGuardrail` as an INPUT content guardrail, so every
dispatched call is judged before the fetch runs: public http and https addresses are
admitted, and loopback, private and link-local addresses, the cloud metadata endpoints among
them, are refused. Redirects are followed by the tool itself, at most 5 of them, and each
target passes the same policy before it is fetched, so a page the policy admits cannot lead
the fetch to an address it refuses; the artifact's `url` is the address fetched in the end.
A deployment with another policy, a domain allow-list for instance, subclasses the tool and
overrides `addressPolicy()`.

---

## Usage

Include tools in a thinker's tool list:

```java
@Override
protected List<Class<? extends Tool>> declareDefaultTools() {
    return List.of(
        CurrentTimeTool.class,
        DateCalculatorTool.class,
        WebFetchTool.class
    );
}
```

The calculators and the clock need no guardrails (no scope, no resources, no side
effects). `web_fetch` declares `UrlGuardrail` itself. Every tool's input/output POJO pair lives in
this package beside it.

> **Example:** [Hello, model](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/hello/PACKAGE.md) -
> `quick_llm_question` from a plain `main`: start the runtime, ask, print the typed answer.
