# ai.redouble.nucleo.prompt

The instructions a thinker gives its model are text that people will want to change after the
code ships: reworded for one customer, tried in two variants, kept in a database where an
operator edits them. If the instructions are a string built inside the thinker, every change
is a release. In Nucleo they are prompts: units of instruction registered under a key, which a
deployment can substitute without touching the class, and which guardrails check before any
model reads them.

## A thinker's instructions

[Your first agent](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
already uses the simplest form. `OrderAgent` returns its instructions from
`getSystemPromptText()` and marks the method `@StaticPrompt`:

<!-- sample: ../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/OrderAgent.java#agent -->
```java
public class OrderAgent extends SingleObjectiveThinker<OrderQuestion, OrderAnswer> {
    public OrderAgent(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class));
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You answer a customer's question about their orders. Look up every order the
            question is about with order_status before you answer, and state each status as
            the lookup returned it.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(OrderStatusTool.class);
    }
}
```

The first time the thinker runs, its text is registered as the default for a key named after
the thinker class, here `ai.redouble.examples.agent.OrderAgent`, and from then on the thinker
takes its instructions from the registry under that key. A deployment that substitutes that
key (below) changes what the agent is told, and the class stays as it is. `@StaticPrompt`
declares that the text never changes, which lets Nucleo check it once and keep it.

## Prompts declared by key

A prompt shared by several classes, or one an operator should find under a stable name, is
declared on a static field with an explicit key, and the thinker produces it by that key:

```java
public class SupportThinker extends SingleObjectiveThinker<...> {
    @StaticPrompt("support.triage.system-msg")
    public static final String SYSTEM_MSG = """
            You are a support triage expert...
            """;

    @Override
    protected Prompt getSystemPrompt() throws LLMReadableCheckedException {
        return Prompts.produce("support.triage.system-msg");
    }
}
```

Name keys `<domain>.<feature>.<role>`: hierarchical, lowercase, without colons. The annotation's
value can be left out, and the key is then derived from where it stands: `<class>.<field>` for
a static field or method, `<class>` for a class. The colon is reserved for keys Nucleo mints
itself (`inline:<hash>` for `Prompts.of` one-shots, `skillsjars:<skill>:...` for the text of
skills), which is why your keys never collide with them.

`@StaticPrompt` goes on a `static final String`, on a `static final` field holding a
`StaticPromptSource`, on a static method with no arguments returning one, or on a class that
implements `StaticPromptSource` and has a no-argument constructor. Nucleo finds these
declarations by scanning the classpath the first time any prompt is produced. The scan covers
the package `ai.redouble` unless you name another with `Prompts.setDefaultScanPackage` before
the first prompt is produced, or scan one yourself with `Prompts.scanPackage`. There is one scan
per process: once any scan has run, `scanPackage` does nothing. `Prompts.warmCache()` produces
every registered static prompt at startup, so that no agent pays for the first check.

## Instructions that change per call: `@DynamicPrompt`

Some content legitimately varies: today's date, an environment flag, a feature flag. Declare it
as a `PromptSource`, a function from the key to the content, marked `@DynamicPrompt`:

```java
@DynamicPrompt("rocket.claim-line-id.system-msg")
public static final PromptSource SYSTEM_MSG = key -> {
    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
    return TextNode.valueOf("...TODAY'S DATE: " + today + "...");
};
```

A dynamic prompt is produced and checked on every call, which costs something; use
`@StaticPrompt` whenever the content does not really vary. A `String` field cannot be dynamic
and is refused. A source may read state of the whole process (the clock, the environment, a
feature registry), and nothing that belongs to one call: data that varies per invocation - the
thinker's fields, the caller's objects - belongs in the thinker's input, where the model reads
it beside the prompt.

## Substituting a prompt

A deployment changes what a key produces without touching the code that declared it:

- **`Prompts.replace(key, source)`** substitutes one key; `Prompts.resetOverride(key)` goes back
  to the default.
- **`Prompts.setGlobalBackend(source)`** puts one source behind every key, a database of
  prompts for example; `Prompts.clearGlobalBackend()` removes it.

`Prompts.produce(key)` looks first for a substitution of the key, then for the global source,
then for the default the code declared, and throws `PromptNotFoundException` when there is
none. A prompt from a static source is kept after its first production; a substitution replaces
what was kept. [Prompt sources](sources/PACKAGE.md) lists the sources Nucleo ships - text held
in a database, fetched from a server, or split between two variants - so a deployment rarely
writes its own.

Two more ways to register a prompt without an annotation:

- **`Prompts.of(key, text)`** registers text as the default for a key and produces it. A second
  call for the same key replaces the default; substitutions still apply on top.
- **`Prompts.bindStaticDefault(key, text)`** registers the default only when the key has none,
  and otherwise reuses what is there. This is how a thinker registers its
  `getSystemPromptText()` on every run without discarding the prompt already checked.

`Prompts.of(text)` builds a one-time prompt under a key made from its content, registered
nowhere: nothing can substitute it, and the baseline guardrails still check it.

## Checking prompts: guardrails

A prompt is text that reaches a model, and a deployment may want every one checked: not too
long, no instruction to leak data. Attach checks with a guardrail factory:

- **`Prompts.addGuardrail(key, factory)`** checks one key;
- **`Prompts.addBaselineGuardrail(factory)`** checks every prompt produced, one-time prompts and
  the text of skills included; `Prompts.removeBaselineGuardrail(factory)` takes one off.

```java
Prompts.addBaselineGuardrail(() -> new SizeCapGuardrail(Job.workflow("platform", "prompt-checks"), 20_000));
```

Each takes a factory, because a guardrail is a job and runs once: Nucleo builds a fresh one for
every check. `Prompts.produce` runs the checks and waits for their verdict, and a verdict is
remembered for the same content under the same checks, so unchanged content is checked once. A
refused prompt throws `GuardrailException`. Nothing is attached by default: a deployment wires
the two shipped checks, `SizeCapGuardrail` (a limit on length) and
`ExfiltrationMarkerGuardrail`, itself. Both hold no resources, so checking a prompt from inside
an agent's loop cannot deadlock.

Checks stay attached to the key when its source is substituted, so a deployment cannot bypass
them by swapping the source.

## What to avoid

- **Putting a thinker's state into a registered prompt.** Instance data belongs in
  `ThinkerObjective.input`; prompts hold content that changes rarely.
- **Reading the caller's or the request's state inside `PromptSource.produce()`.** Smuggling it
  through a ThreadLocal counts.
- **A `PromptSource` class per fixed prompt.** Use `@StaticPrompt` on a `static final String`.
- **A `PromptSource` class per A/B test, database lookup or remote fetch.** Use the shipped
  sources (`AbTestSource`, `DbTextSource`, `RemoteTextSource`).
- **Attaching guardrails to a source instead of to the key.** Use `Prompts.addGuardrail(key, g)`.
- **`Prompts.of(text)` as a thinker's instructions.** Register them under a key so deployments
  can substitute them.
- **Building `Prompt` instances directly.** Prompts come from the `Prompts` facade, which is
  what checks and keeps them.

## How it works inside

The types of this package, the resolution order and how its cache stays consistent under
concurrent substitution, discovery at build time, and the wire shape of a serialized prompt are
in [Inside prompts](PROMPT_INTERNALS.md), for those working on the runtime itself.
