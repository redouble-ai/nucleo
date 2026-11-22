# Changelog

Every release of Nucleo, newest first. Released artifacts are on Maven Central under the
group `ai.redouble`; a published version is never changed in place.

## Unreleased

- What a tool tells the model can be changed without a release. A deployment sets an
  `AnnotationResolver` with `Annotations.setResolver`, and from then on the description of a
  tool (`@ToolDescription`), of a class or field of its input and output (`@LLMDescription`,
  `@LLMExample`), its display name and its weight are read from the deployment's store when
  it holds something under the annotation's key, `<class name>@<Annotation>` or
  `<class name>.<field>@<Annotation>`, and from the code otherwise. A stored override is the
  annotation's `value` as text and, apart from it, a JSON object of its other attributes. A
  store restates an annotation the code carries and cannot add one the code does not have. A
  tool served over MCP carries the stored text. `Annotations.changed()` tells Nucleo the
  store changed, and `ClassToolProvider.annotationKeys()` lists what can be stored for a
  tool. With no resolver set nothing changes.
- A thinker finds the right tool in a catalog of hundreds. A thinker with more than 30 tools
  to ask for (`SommelierSettings.listedTools`) is no longer shown them: its `request_tools`
  names no tool, and it has a new tool, `find_tools`, to which its model states what it
  needs and which answers with the tools that fit. The deployment's `Sommelier` does the
  finding. The shipped `ModelDrivenSommelier` has a model of the `SMALL` grade read every tool in
  full, in parts of about 50,000 tokens for a long catalog; it is measured with Claude Haiku
  5.5 and stronger models, and a deployment names another model with a `ModelRole` or sets
  its own `Sommelier` on `ToolHub`. A thinker with 30 tools or fewer is unchanged. For a
  thinker with more, the definition of `request_tools` changes: it no longer enumerates the
  catalog.
- A search tool tells the model how much it found. `SearchResultArtifact`, alias
  `search_result`, is a new artifact a tool returns in place of a bare list: the total of
  matching rows in the whole store, and one window of them as a `ListArtifact`. The model is
  shown the total and the list's digest whatever the size of the window, and every row is
  held under its own reference.
- A model reads the rows of a list it holds. A list is shown to a model as its count and
  first few rows, and until now the model had no way to see the others. Every thinker has a
  new tool, `read_rows`: the model names the list, the first row and how many, ten by
  default, and sees those rows in full from then on.
- A date in a tool result reaches the model as a date. Every `java.time` value was written
  in Jackson's numeric form, so a model read `[ 2021, 7, 28 ]` for a date and
  `1779987029.597415000` for a timestamp, while the tool's schema told it to write
  `"2021-07-28"`. A `LocalDate` is now written `"2021-07-28"`; an `Instant`, `OffsetDateTime`
  or `ZonedDateTime` as ISO-8601 text with its offset and no zone name; a `LocalDateTime` and
  a `LocalTime` as ISO-8601 text with none. A timestamp read keeps the offset it was written
  with, where it used to be moved to UTC. An `Instant` field's schema is now a string of
  format `date-time` (it was published as an object), and a `LocalTime` or `OffsetTime`
  field's a string of format `time` (it was `date-time`), which the MCP input check enforces.
  Anything that stores or sends
  what `NucleoJsonSerializer` writes carries the new text from now on: a consumer outside
  Java that read a timestamp as a number reads a string. Values stored in the numeric forms
  are still read.
- A tool whose base class declares its own type parameters gets its real input type. The
  input and output of a tool were read by position from the first generic superclass, so a
  tool extending `AbstractLookupTool<Row, Input>` was published with the schema of `Row`
  and failed every call from a model with a `ClassCastException`. They are now the types
  bound to `I` and `O` of `Tool<I, O>`, traced through the hierarchy.
- The artifacts a called thinker selects reach the thinker that called it. A thinker that ran
  as another thinker's tool answered with references the calling model could read and its
  registry did not hold: an artifact tool given one answered that the artifact was not found,
  and a calling answer that named one left without it. The called thinker's answer is now
  written for the calling model with the selected objects in the place of `artifact_refs`,
  each as `{"@ref": ...}`, and that write registers them in the calling conversation.
- An answer that names a reference its registry does not hold is refused. Such a reference
  was logged and skipped, and the answer left with fewer artifacts than it named. The model
  is now told which reference names nothing and asked again, under the same budget as a
  missing required field; past the budget the call fails with `ResponseValidationException`.
  `ChatAnswer.stored` still reads an answer an earlier release stored with such a reference.
- Breaking: `ThinkerOutput.resolveArtifacts(registry)` is
  `resolveArtifacts(registry, violations)` and is called by the reply gate,
  `ThinkingResponseHandler.heldArtifacts`. A thinker of your own that extends
  `AbstractThinker` and called it after reading the answer drops the call.
- Security: `nucleo-demo-poi` declares `commons-lang3` 3.20.0 and `log4j-api` 2.26.1 as its
  own dependencies. The module, and a host that included it without a platform BOM, took
  3.16.0 (CVE-2025-48924) and 2.24.3 (CVE-2026-49844) from Apache POI 5.4.0. The two demo
  hosts already resolved fixed versions of both, and the module is not published.

## 0.1.4 (2026-11-19)

- A thinker publishes no reasoning notification for a reply that carries no reasoning. A
  `SingleObjectiveThinker` published one titled "Reasoning" with a null message after every
  such reply: a model whose thinking is native that wrote no thinking block, or a reply that
  was its JSON and nothing else.
- Breaking: `SingleObjectiveThinker.logReasoning` is gone. Both thinkers publish through
  `AbstractThinker.publishReasoning(response, title, context)`; a subclass that overrode
  `logReasoning` overrides that method.
- Security: `nucleo-bom` manages `httpcore5-h2` at the version it manages `httpcore5`. An
  application on Spring Boot 4.0 that imported the BOM first resolved `httpcore5` 5.4.4 beside
  `httpcore5-h2` 5.3.6, the version CVE-2026-54428 names.
- Security: the two demo hosts move past ten published advisories. Spring Boot goes from
  4.1.1 to 4.1.2, which takes the Spring Boot demo to Tomcat 11.0.27 (CVE-2026-65182,
  CVE-2026-65637, CVE-2026-65905, CVE-2026-68525) and Jackson 3.1.7 (CVE-2026-68497,
  CVE-2026-91776, CVE-2026-91777, CVE-2026-89407, CVE-2026-89425). The Quarkus demo manages
  `log4j-api` at 2.26.1 (CVE-2026-49844). The demos are run from a checkout and are not
  published; no artifact on Maven Central carried any of the three libraries.
- A reply format is sent once per request. The format of a structured reply, a sentence and
  the reply's schema, was appended to every turn that asked for a reply, on every render, so
  a conversation carried one copy per model call it had made: 35 copies in one measured
  request, half of its input. The format is now defined once, in the system content, under a
  name built from its classes (`OrderAnswer` for a one-object reply,
  `ThinkingResponse_of_OrderAnswer` for a thinker's), and each asking turn ends with one line,
  `Reply in the JSON format <name>, defined above.` A thinker with tools says in that line
  that the reply is a tool call or the final answer. The definition is written at each render
  for the model the render is for and is stored nowhere, so the same conversation sent to a
  model with other capabilities carries that model's definition, and a request never holds
  two definitions of one name. A one-call structured reply moves the same way: its format is
  in the system content and its user turn ends with the line.
- Breaking: `ResponseHandler` states its reply format through `formatName()`,
  `formatDefinition(ResponseRequirements)` and `replyInstructions(ResponseRequirements)`.
  `responseInstructions()`, `responseInstructions(boolean, boolean)` and
  `usesSchemaNotation()` are removed. A handler of your own returns its format's name (null
  for a plain-text reply, which is what `usesSchemaNotation()` returning false meant), the
  text that defined the reply as its definition, and the line to put after a turn;
  `ResponseRequirements` carries the two flags the old overload took, and a later provider
  requirement becomes a component of it. Two reply classes with the same simple name asked
  for in one conversation share a format name, and the first one's definition stands: give
  them different names.
- A restored conversation renders the turns the model had answered as it did before the
  save: each ends with the reply line again, taken from the handler the conversation is
  asked with now. Since 0.1.3 such a turn rendered as its content alone, so a thinker's
  first turn, which holds nothing but that line, was empty after a restore and the request
  started with the model's turn, which a provider refuses.
- `ContextEstimator.rendering(conversation, spec)` returns the rendering an estimate counts.
  An estimator of your own that extends `AbstractContextEstimator` has it; one that
  implements the interface directly adds it.

## 0.1.3 (2026-11-14)

- Security: the BOM pins three dependencies past a published advisory.
  `io.micrometer:micrometer-core` 1.16.6 (GHSA-g3pr-3p32-fp23, GHSA-w737-wx49-qj23),
  `org.apache.httpcomponents.client5:httpclient5` 5.6.3 (GHSA-hjcp-jmpx-g3qm) and
  `io.opentelemetry:opentelemetry-api` 1.67.0 (GHSA-ccrj-gw8r-854m). An application that
  imports the BOM takes them with this release.
- A chat exchange whose closing call still asks for tools fails the turn. When a chat
  spent its tool budget and the one call instructed to answer returned tool calls again,
  the exchange was saved as a success, ending in tool calls with no results, and the
  provider refused every later turn of that conversation. The exchange now ends as any
  failed exchange does: the conversation is rolled back to the person's message
  ([#22](https://github.com/redouble-ai/nucleo/issues/22)).
- A turn no model answers is sent as its content alone. Every turn restored from a stored
  conversation and every compaction summary carried the plain-text response handler, so
  each was sent with `Response must be a string` after it. A restored turn that was
  answered before the save and a summary carry no handler; the summary that replaces the
  turn about to be answered takes that turn's handler and output budget
  ([#22](https://github.com/redouble-ai/nucleo/issues/22)).
- Deprecated, to be removed no later than 1.0: `MessageSnapshot.setEnableCache(boolean)`
  and the reading of a plain-string answer in `ChatAnswer.stored`. Both read conversations
  stored by a release before 0.1.3.
- A cancel reaches a job that is starting, and a chat holds its sub-agents to its own
  depth: `JobDispatcher.cancel` missed a job handed to the executor and not yet running
  ([#24](https://github.com/redouble-ai/nucleo/issues/24)), and the depth limit of a
  sub-agent was skipped when the caller was a chat
  ([#21](https://github.com/redouble-ai/nucleo/issues/21)). A role whose entry is of
  another kind, or on no rung, is served from the role's grade
  ([#20](https://github.com/redouble-ai/nucleo/issues/20)).
- The spec classes choose a catalog entry's class, and the catalog names none. An entry is
  loaded into every spec class on the classpath, each answers `match()` (0 for an entry that
  is not its own, a higher number for a narrower fit, `AbstractModelSpec.DEFINITELY_MINE`
  for the entry it was written for), and the highest answer is kept; on equal answers a
  superclass is kept over its subclass. So a spec class shipped after a catalog was written
  takes the entries it recognizes without the file being touched. A class is found by
  naming it in `META-INF/services/ai.redouble.nucleo.harness.models.AbstractModelSpec`. The
  runtime's load warns once of the properties entries state that their class has no field
  for. Breaking: `spec_type` is no longer read, and a catalog that states it is warned of
  it as an unread property; `ClientProvider.specTypes()`, `ClientProviders.specClass` and
  `AnthropicModelSpec` are removed, the Anthropic entries being standard ones; a subclass
  of `AbstractModelSpec` has to implement `match()`; an entry with no platform on record
  loads whatever its catalog said of its class
  ([#30](https://github.com/redouble-ai/nucleo/issues/30)).

- An artifact a thinker's answer refers to in a text field is selected for every answer
  class. The references in the text were looked for in the answer's `toString()`, which for
  an answer class that does not override it holds no reference, so an artifact named in a
  text field and not repeated in `artifact_refs` was dropped without a word. They are now
  read from the reply as the model wrote it, before it becomes an object: every string
  value, at any depth, and no field name. `ArtifactRegistry.refsWrittenIn` does the
  reading. Breaking: `ArtifactResponse.canonicalizeArtifactRefs` takes the references found
  in the reply in place of an answer text; `ThinkerOutput.resolveArtifacts` resolves
  `artifact_refs` and reads no text, so an answer object built in code selects what its list
  names; `ChatAnswer.toString()` is no longer its JSON.
- The discovery learns a new entry's thinking mode from the provider. A new model was
  written with the mode of its older relative and pinged with thinking off, so
  `claude-haiku-5-5` inherited `EXTENDED`, was reported reachable, and refused the first
  call that asked it to think. The mode is now the one the provider's listing states
  (Anthropic's does), else the one the model accepts on a call that asks it to think; the
  report lists each correction under `THINKING MODES`. Every chat entry is asked, an entry
  written `NONE` and an entry already in the deployment's file included, and a way of
  thinking counts only when the answer carries reasoning; `ClientProvider.thinkingModes()`
  says which ways a provider's client can ask. Breaking: `DiscoveredModel` gains
  the component `thinkingMode` and `CatalogDiscovery.Result` the component `thinkingModes`
  ([#28](https://github.com/redouble-ai/nucleo/issues/28)).
- A model's spec computes what a call on it costs: `ModelSpec.cost(TokenLedger)`, a default
  method holding the rule every shipped spec prices by. A spec class of your own overrides
  it to price a model its provider bills by another rule (a long-context rate, a deployment
  zone, a service tier), and a reported usage, a reservation and a spending cap all follow
  it. Breaking: `Cost.of(ModelSpec, long, long, long, long)` is replaced by
  `Cost.of(TokenLedger)` ([#25](https://github.com/redouble-ai/nucleo/issues/25)).
- A catalog entry keeps every rate its provider's price list states for the model, as
  `listed_rates`: the row's name as the provider writes it to the rate per million, tiers
  and long-context rates included. The discovery writes them for Bedrock entries from the
  AWS price list; `ModelSpec.getListedRates()` returns them. Nothing prices a call from
  them unless a spec class of your own does. Breaking: `ModelSpec` gains
  `getListedRates()`, which an implementation outside `AbstractModelSpec` has to add;
  `DiscoveredModel` gains the components `listedRates` and `priceOptions`
  ([#26](https://github.com/redouble-ai/nucleo/issues/26)).
- The discovery reads Microsoft's public retail price list for an Azure AI Foundry resource
  and writes the rates of each deployment's base model into `listed_rates`, Global and Data
  Zone side by side. It writes no price, because the listing does not say which type a
  deployment is; the report names an entry whose typed price is neither type's. Breaking:
  `CatalogDiscovery.Result` gains the component `pricesThatAreNoOption`
  ([#27](https://github.com/redouble-ai/nucleo/issues/27)).
- A chat's answer is an object, and only the artifacts it selects leave the chat. A chat
  answered with a string, which had no place for a selection, and `ChatThinker` published
  every artifact any tool had registered in the conversation with each answer. The answer
  is now a `ChatAnswer`: `message_text`, the markdown for the person, beside `artifact_refs`.
  The model selects artifacts as it does in every other thinker, by naming them in
  `artifact_refs` or writing a reference in a field, and the answer's `ContentStreamEvent`
  carries the answer object and those artifacts keyed by reference. An application extends
  `ChatAnswer` for fields of its own. `ChatAnswer.stored` reads an answer and its selection
  back from a stored assistant message; an answer stored as a string reads as its text with
  no artifacts. Breaking: `ReactiveThinker` and `ChatThinker` take the answer class as a type
  parameter and a constructor argument; `streamResponse` takes the answer object in place of
  a string and a map; the model writes `{"answer": {"message_text": "..."}}` where it wrote
  `{"answer": "..."}`; a reader of `ContentStreamEvent.getArtifacts()` receives the selection
  where it received the registry. `SingleObjectiveThinker.resolveArtifacts` moved to
  `ThinkerOutput.resolveArtifacts(registry)`.

- A provider fault that outlasts a job's retries is no longer retried again by every job
  above it. When a model call spent its upstream retries, the doer or thinker awaiting it
  found the spent signal in the failure's cause chain and ran its own whole body again, and
  so did the job above that: with the default of three retries, a call under a thinker under
  a doer was sent 64 times where 4 were meant, and every step of the doer ran four times,
  the ones that write included. `LLMReadableCheckedException.unwrap` and `wrapWithContext`
  now rethrow a retry signal only when nothing LLM-readable lies above it in the chain, so a
  job's exhausted failure travels up as a failure and each job above runs once. The same
  holds for a second output truncation, for the correction backstop, and for a job that
  timed out or was cancelled with a signal in hand; a chat turn reported to its user as
  failed is no longer run again by the job above it; and a stream that failed after
  delivering text stays unretried through a tool's broad catch. The three failures now name
  the job whose retries were spent. Behaviour change: code that wraps a retry signal in an
  LLM-readable exception of its own ends that call's retry, and a parent that wants to retry
  a failed child says so in its own retry policy
  ([#29](https://github.com/redouble-ai/nucleo/issues/29)).

- A failed model call states the request id the provider gave it, the id the provider's
  support asks for. When an error response carries one, the exception a client builds from
  it ends in `(request id <id>)`, in its message and in the details quoted when a job's
  retries end; every failed chat call logs one WARN line with the job, the model, the
  endpoint, the failure and the id; and the failed call's record carries the id in
  `LLMResponse.getProviderRequestId()`. Read by the Anthropic client (direct and on
  Bedrock, where the id was in a response header and nowhere in the failure), the Bedrock
  Converse and Cohere embeddings clients, and the OpenAI SDK clients. A prompt the provider
  refused as too long now keeps the provider's failure as the cause of
  `TokenEstimateExceedsLimitException`. **Breaking** for a class that implements `Client`,
  `LLMClient`, `EmbeddingsClient` or `DecisionClient` directly: it implements
  `providerRequestId(Exception)`, returning null when it reads no id. A client that extends
  one of the abstract clients needs no change
  ([#29](https://github.com/redouble-ai/nucleo/issues/29)).
- Cancelling a job no longer fails when another job starts or ends at the same moment.
  `JobDispatcher.cancel` walked the running jobs without their lock, so under load it threw
  `ConcurrentModificationException` and left the job it was asked to stop running.
- A conversation longer than three turns is cached to its end on Anthropic. The client gave
  its cache breakpoints to the first three turns and never moved them, so from the fourth
  call on nearly all of each request was billed at the full input price. A message now
  states how cacheable it is, `Cacheability` (`ALWAYS`, `PREFERRED`, `AUTO`, `UNLIKELY`,
  `NEVER`), and each provider's client decides what to do with that; the Anthropic client
  puts a breakpoint on the system content, on the first turn and on the last turn, which
  moves forward with every call. Breaking: `Message.setCache(boolean)` and
  `isEnableCache()` are replaced by `setCacheability(Cacheability)` and
  `getCacheability()`, where `setCache(true)` is `Cacheability.PREFERRED`;
  `ProcessedMessageData.cacheEnabled` is replaced by `cacheability`. A stored conversation
  is read as before.
- OpenAI models that take cache breakpoints, GPT-5.6 and later, are cached by them, on the
  Responses and the Chat Completions API. Such a model is told to store only what is marked,
  and the client marks the system content, the end of the conversation and the messages
  the conversation's `Cacheability` values allow, so a message stated `NEVER` is not
  stored. The catalog says which models these are: `cache_breakpoints` is now a field of
  every entry (`ModelSpec.getCacheBreakpoints()`), set on the shipped `gpt-5.6` and
  `gpt-6-astra` and on their Azure AI Foundry deployments, and the Anthropic client takes
  its count from it. A response's usage reports the tokens a call stored
  (`cache_write_tokens`), which these models bill at 1.25 times the input price and which
  was counted as plain input until now; the shipped entries state that price
  (`cache_write_price_per_million`). Breaking:
  `OpenAITokenLedger`'s constructor takes the cache-write count, and a `ModelSpec`
  implemented outside `AbstractModelSpec` has `getCacheBreakpoints()` to implement.
- A chat thinker no longer writes assistant turns of its own. After a final answer it added
  a second assistant message holding the answer's text, behind the reply the model had
  given, so every later call replayed each answer twice, the second copy ending in
  `Response must be a string`, and a model several turns into a chat wrote its replies the
  same way: the reply object, the answer again as prose, and that line. The model's reply is
  now the only record of an answer. When an exchange fails, the note saying why is added
  after the person's message on the user side, worded as the runtime speaking; it was an
  assistant message in the model's voice
  ([#22](https://github.com/redouble-ai/nucleo/issues/22)).
- A sub-agent started with `sub_thinker` works at most one level of depth below its caller,
  the step stopping at `STANDARD`, so a chain of sub-agents ends whatever depth the model
  asks for. A thinker that needs its sub-agents at its own depth overrides
  `AbstractThinker.subAgentsWorkOneLevelBelow()`
  ([#21](https://github.com/redouble-ai/nucleo/issues/21)).
- A model role names the model for one kind of work, wherever that work is done:
  `ModelRole(name, catalogId, grade)`, a constant the application declares once and every
  call doing the work declares in place of a grade, through `requireModel(ModelRole, ...)`,
  `ThinkerDeclaration(ModelRole, ...)` or `ConversationContext.setRole`. The picker serves
  the role's entry when it can and the role's grade otherwise, with one warning per role and
  reason, and the call record carries the role's name. The documentation now recommends one
  home per kind of choice: the deployment's choice per grade in the catalog's `pins`, the
  application's choice for one kind of work in a role; Nucleo's own pickers name no model.
  Breaking: `Seat` gains a `role` component (the three-argument constructor stays for a
  grade seat), `CostLedger.Call` a `role` component after `servedModelId`, and
  `AbstractModelPicker` gains the protected `whyNot`, `whyNotProvisioned` and
  `whyNotCallable` ([#20](https://github.com/redouble-ai/nucleo/issues/20)).
- The shipped picker always serves the best model available. A request is served from its
  grade, else from the nearest grade above, else from the nearest grade below, with a
  warning in the log each time the picker moves, and fails only when no entry the walk
  reached can be called; an entry the compliance envelope refuses or the availability probe
  reports down is passed over like one with no credential. `ModelSettings.fallbackToStronger`
  and `ModelSettings.fallbackToWeaker`, both on by default, switch the two directions off.
  `Grade.CEILING` is a request for the strongest model the application may call, served
  from `MEGA` downward. Breaking: the gate no longer refuses an entry below the seat's grade,
  no longer lowers a seat to `ModelPicker.ceiling()`, and hands a `CEILING` seat to the
  picker as declared, so a picker of your own that switches over the grade needs a `CEILING`
  case; `ModelPicker.ceiling()` is read for reporting and by the benchmark alone
  ([#19](https://github.com/redouble-ai/nucleo/issues/19)).
- The catalog discovery prices every Bedrock entry from the AWS price list, the public files
  AWS publishes per region, instead of inheriting prices from an older entry or keeping what
  a person typed: the regional rate for a `us.` or `eu.` profile, the global rate for a
  `global.` profile, cache rates where the list states them, and the report shows each
  entry's rates before and after. A listed price replaces a typed one on an entry already in
  the deployment's file, the one fact of such an entry a run rewrites. Breaking:
  `DiscoveredModel` gains a `prices` component, and `CatalogDiscovery.EntryReport` a
  `prices` component between `rpmAfter` and `wireBefore`
  ([#18](https://github.com/redouble-ai/nucleo/issues/18)).

## 0.1.2 (2026-10-16)

- The conversation composes the turn the model answers. A caller adds what it has through
  `ConversationContext`, `addText`, `addPojo`, `addImage`, `addFile`, `addToolResult` or
  `addUtterance`, and every add writes into the turn about to be sent; `setResponseHandler`
  names the handler the answer is parsed with and `setRequestedOutputTokens` caps the one
  call. The schema legend and a tool declared after the first call are blocks of that turn,
  so a provider never receives two user messages in a row, and caching every turn is the
  conversation's policy, `setCacheTurns`. Hand-built messages stay legal for a history
  another system holds. Breaking: `ResponseCorrection.exchange` throws
  `IllegalStateException` when the last outgoing message has no response handler; the legend
  and a tool announcement are no longer messages of their own in `getMessages()`; a thinker's
  `cacheAllMessages` is applied through `setCacheTurns`, not written onto each message
  ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- A tool call that leaves an `@LLMRequired` field out, or blank, is refused by the field's
  name before the tool runs, the same correctable refusal a value of the wrong type gets, so
  the model corrects its call instead of the tool failing on a null. The MCP server gives an
  outside client the same validation error. Breaking: `ClassToolProvider.parseInput` throws
  `InvalidInputException` for such an input ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- A catalog entry closed as `UNLISTED` or `UNREACHABLE` that a later discovery lists again is
  pinged with its spec already reopened, so it comes back instead of being closed again on
  every run. The command-line discovery starts the dispatcher its probes run on and shuts it
  down after the run ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- An embeddings or decision ping reports the first classifying signal in its cause chain, as
  a chat ping does; the character-based counter prices the floor only for a payload that is
  not base64. Breaking: `new ImageBlock(null, ...)` and `new FileBlock(null, ...)` throw
  `IllegalArgumentException`, and `TokenCounter.countImageTokens`, `countFileTokens` and
  `ImageDimensions.of` no longer accept a null block
  ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- Token accounting is one `TokenLedger` per call, in the new `ai.redouble.nucleo.harness.tokens`
  package: `ModelSpec.estimate(conversation)` returns the estimate the compaction trigger, the
  reservation and the client's pre-flight all read, and `LLMResponse.getUsage()` returns the
  usage the provider reported in the same shape. Counting is the provider module's: each
  `ClientProvider` supplies a `ContextEstimator` and a `TokenCounter` for its models, so tool
  calls, tool results, images, thinking blocks and cache counters are counted by the provider's
  own rules, and OpenAI's own endpoints count on `o200k_base`. Breaking: `LLMResponse` loses its
  scalar usage getters and setters (`getActualInputTokens`, `getActualOutputTokens`,
  `getCacheCreationInputTokens`, `getCacheReadInputTokens`, `getLastOutputTokens`, `hasCache`,
  `getCacheHitRate`, `setUsage(int, ...)`, `setAdditiveUsage`, `setActualInputTokens`);
  `ConversationContext.getTotalTokens`, `Message.getEstimatedTokens`, `Message.getTokens`,
  `Message.getActualTokens`, `TokenizerFactory` and `ContextWindowManager.absoluteLimit` are
  gone; `ModelBinding.getReservation()` returns the ledger; the token counters move to the new
  package and `Cl100kTokenCounter` becomes `TiktokenCounter`; `PreparedConversation` carries its
  registry section as a component; the pre-flight now reserves the thinking budget on a thinking
  model ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- An image is priced by its provider's published rule from the pixel size in its header: OpenAI's
  85 tokens plus 170 per 512-pixel tile after scaling, Anthropic's pixels over 750 after scaling
  with a ceiling of 1600, on Bedrock too. Breaking: `TiktokenCounter` takes four constructor
  arguments, the per-tile allowance is gone, and `Cost.reserved` is removed, since a reservation
  is a `TokenLedger` that prices itself ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- Compaction works on exchanges, an assistant turn with the turns that answer it, so a tool call
  and its result leave or stay together and the provider never sees one without the other; a
  tool result is detected by its block, its payload is what the summary model reads, LIGHT
  shrinks the payload inside the block, and every level checks the pairing before it replaces
  the conversation. A summary model's answer must be the asked-for object; an answer outside that
  shape is a failed summary, never prose that replaces content. Compaction measures the input
  alone: the room a call needs for its answer is the pre-flight's question. A conversation
  marked non-compactable is never compacted on any
  path; when it does not fit the model it fails with the new `NonCompactableContextException`,
  distinct from `ContextOverflowException`, which says compaction was tried and was not enough
  ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- Every exchange with a model inside the runtime is a tool built on `AbstractModelDependentTool`:
  a thinker's turn (`LLMCall`), a compaction summary, each call of `SummarizationTool`, which is
  now a doer submitting one `SummaryCall` per exchange, and the catalog's probes. One door means
  one correction protocol: a compaction summary that answers outside its shape is corrected like
  any typed answer, up to twice, before it fails; a probe keeps its own rule of one call with no
  retries of any kind. The summarizer cuts an oversized text by the model's own tokenizer at
  every step, down to characters, so a text with no paragraph or sentence breaks is chunked
  rather than refused again. Breaking: `LLMCall` extends
  `AbstractModelDependentTool`, so a subclass overriding `client(JobResources)` declares it
  `protected`; `SummarizationTool` has no timeout of its own, its calls carry the 6-minute guard;
  `CatalogPing.harvest` takes any `LLMResponse` and `CatalogPing.classify` any `Throwable`
  ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- A thinker's input reaches the model as the JSON of its fields inside the objective, and
  `ThinkerInput` has no rendering method to override
  ([#2](https://github.com/redouble-ai/nucleo/issues/2)).

## 0.1.1 (2026-10-02)

- Every published artifact carries a CycloneDX software bill of materials, attached as
  `<artifact>-<version>-cyclonedx.json` and signed with it.
- `NOTICE` carries the copyright and licence only. The statement on Redouble AI's patents
  is in the README's License section, where it can be explained.
- A model can no longer author an artifact: an artifact-typed field takes a reference, and
  the caller receives the registry's object (GHSA-m8pq-5898-5q8j).
- `WebFetchTool` declares its address policy itself and applies it to every redirect
  (GHSA-5h79-cgv5-c74w).
- An answer still missing a required field after its corrections fails the call
  ([#13](https://github.com/redouble-ai/nucleo/issues/13)).
- A cancelled job that then fails settles as cancelled and releases its caller
  ([#14](https://github.com/redouble-ai/nucleo/issues/14)).
- The demo's extract, pricing and decision runs read the corpus the demo ships, located by
  the engine; no request names a folder to read or a file to write, and the pricing report
  is the response alone. The page shows the folder in place of an input.

## 0.1 (2026-10-01)

The first public release.

- The runtime: jobs on virtual threads with capacity admission, typed tools, thinkers and
  doers, scopes and guardrails, byte-identical artifacts, prompt and skill registries, the
  model catalog with its discovery, and the observability bus.
- Provider modules: Anthropic, OpenAI and OpenAI-compatible endpoints, Azure AI Foundry,
  AWS Bedrock through Converse and through the Anthropic SDK, and System One decision
  models.
- Hosts: `nucleo-spring-boot-starter` for Spring Boot 4 and `nucleo-quarkus` for Quarkus,
  the latter building to a native image.
- `nucleo-examples`, a demo running the same work on both hosts, and the documentation
  site at [docs.redouble.ai](https://docs.redouble.ai/).

Known gaps, each tracked as an issue:

- [#1](https://github.com/redouble-ai/nucleo/issues/1): the `MAXIMUM` compaction level runs
  the same collapse as `AGGRESSIVE`, so a conversation whose non-compactable messages
  alone exceed the context limit is refused rather than made to fit.
- [#2](https://github.com/redouble-ai/nucleo/issues/2): `ThinkerInput.toLLMString` renders
  a thinker's input as ad-hoc text rather than strict JSON.

What is unstable at 0.1: the public API has not been through a second release, so a 0.2
may change signatures where a first consumer shows a better shape. Each such change will
be named here.
