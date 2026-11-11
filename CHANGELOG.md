# Changelog

Every release of Nucleo, newest first. Released artifacts are on Maven Central under the
group `ai.redouble`; a published version is never changed in place.

## Unreleased

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
