# Inside prompts

For people working on the runtime itself. How a prompt is declared, substituted and checked
is in [PACKAGE.md](PACKAGE.md); this page is the machinery behind it: the types, the
resolution order, how the cache stays consistent under concurrent substitution, and the
wire shape.

## Surface

| Type | Role |
|------|------|
| `Prompt` | Interface: `key()`, `content()`, `context()`. Open hierarchy. |
| `TextPrompt` | Baseline record `(key, JsonNode content)`. Sole concrete impl shipped. |
| `PromptContext` | Lineage: `contentHash`, `producedAt`, `version`, `variantTag`. `@JsonIgnore`. |
| `PromptSource` | Functional interface: `JsonNode produce(String key)`. |
| `StaticPromptSource` | Marker sub-interface. Content for a given key never changes; cacheable. |
| `Prompts` | Governor facade - the only public construction path. |
| `@StaticPrompt` | Declares a member as a registered prompt source whose content never changes. Cached + validated once. Default choice. |
| `@DynamicPrompt` | Declares a member as a registered prompt source whose content may vary per call. Re-produced + re-validated every call. Use sparingly. |
| `PromptScanner` | Discovers `@StaticPrompt` / `@DynamicPrompt` across the classpath via Reflections; package-private. |
| `PromptJacksonModule` | Registers custom Jackson serializer/deserializer (wire shape: `{key, content}`). |
| `PromptNotFoundException` | `UncorrectableLLMException` thrown when `produce(key)` finds no source. |
| `PromptRegistrationException` | Scanner-time programmer error (duplicate key, invalid placement). |

## Resolution order

`Prompts.produce(key)` resolves in order:

1. **CACHE** - pre-validated Prompt held for StaticPromptSource keys. Guardrails re-check under current fingerprint.
2. **OVERRIDES** - `Prompts.replace(key, src)`.
3. **GLOBAL_BACKEND** - `Prompts.setGlobalBackend(src)`.
4. **DEFAULTS** - populated by the `@StaticPrompt` / `@DynamicPrompt` scanner.

A miss in every tier throws `PromptNotFoundException`.

## Invalidation

- `replace(key, src)` and `resetOverride(key)`: `CACHE.remove → OVERRIDES.put/remove`. Reverse order prevents concurrent readers from observing a stale cache alongside a freshly-published source.
- `setGlobalBackend(src)`: `CACHE.clear → GLOBAL_BACKEND = src`.
- `clearGlobalBackend()`: `CACHE.clear → GLOBAL_BACKEND = null`.
- The produce side holds up the other half: after publishing a static source's prompt to
  the cache, `produce` re-resolves the key and evicts its own entry when a substitution
  landed mid-produce - the registration-side ordering alone cannot cover that
  interleaving, and without the re-check the stale prompt would shadow the new source
  forever.

## Discovery

The first `produce` (or `of`) auto-scans `ai.redouble` for annotated declarations; redirect
with `Prompts.setDefaultScanPackage` before any produce, or scan a package eagerly with
`Prompts.scanPackage`. One scan per process: once any scan has fired, a `scanPackage` call
is a silent no-op. `Prompts.warmCache()` eagerly produces every registered static default.

## Guardrail validation

Per-key (`Prompts.addGuardrail(key, Supplier<Guardrail<Prompt>>)`) and framework-wide
(`Prompts.addBaselineGuardrail(...)`) registrations both take a FACTORY, not an instance: a
guardrail `Job` is one-shot, so the registry holds something that can mint a fresh one per
validation. Validation is synchronous from the caller's view: `produce()` dispatches each
guardrail through the `JobDispatcher` and blocks on it, memoizing the verdict by (content
hash, guardrail-chain fingerprint) so unchanged content under an unchanged chain validates
once. Substitution preserves validation: `replace(key, ...)` does not remove the guardrails
attached to that key.

## Serialization

Custom Jackson module ships as part of `NucleoJsonSerializer`. Wire shape:

```json
{"key": "pharma.drugbank.system-msg", "content": "You are a DrugBank expert..."}
```

`PromptContext` is never emitted (lineage is computed once per `(key, content)` and weakly
memoized, so repeated `context()` calls never rehash). Content polymorphism (TextNode for
text, ObjectNode for structured) lives inside the `content` field; the Prompt type itself
is not polymorphic. Deserialization refuses a non-object document and a missing `key`
(surfacing as an `IOException` naming the problem); absent `content` restores as an
explicit JSON null node, never a Java null.

## Construction

Construction goes through `Prompts.buildPrompt` internally; there is no public constructor
path, which is what lets the facade check and keep every prompt it hands out.
