# ai.redouble.nucleo.prompt.sources

[Prompts](../PACKAGE.md) showed how a deployment substitutes what a key produces with
`Prompts.replace(key, source)` or puts one source behind every key with
`Prompts.setGlobalBackend(source)`. Either way it needs a `PromptSource`, and the usual
needs are the same everywhere: text from a database, text from a server, an experiment
between two wordings. This package ships those, so a deployment plugs one in instead of
writing a source class of its own.

- **`DbTextSource`** looks the content up by key through a `Lookup` function you hand it.
  The function is yours, so your session, transaction or DAO stays where it lives and
  Nucleo needs to know nothing about it.
- **`RemoteTextSource`** fetches the content by key through a `Fetcher` you hand it, and
  the HTTP mechanics stay yours the same way.
- **`AbTestSource`** answers from one of two inner sources, choosing by probability:
  `probA` between 0 and 1, checked at construction, decides how often the first branch
  answers. Wrap the current wording and the candidate wording, and the traffic splits
  without a release.
- **`StaticTextSource`** is a fixed string captured at construction. You rarely build one
  yourself: it is what the scanner mints for every `@StaticPrompt` string constant.

`StaticTextSource` carries the `StaticPromptSource` marker, so a prompt produced from it is
kept after its first check. The other three answer per call, since their content can change
under the key; the guardrail verdicts are remembered by content, so a check repeats only
when the content actually changed.
