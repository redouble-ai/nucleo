# ai.redouble.nucleo.util

The small helpers the runtime itself needs: parsing XML safely, printing counts and durations
for people, reading the dates and yes-or-no values a model writes its own way, cutting text,
and building the classes a deployment names in its configuration. They live here so that the
published nucleo-core artifact depends on no utility library. Each is a class of static
methods, safe to call from any thread, and any code of yours may use them.

## SafeXml: parsing XML from outside

XML from outside the process can be written to attack the parser that reads it: an XML
external entity (XXE) makes the parser read a local file or call a host and put the answer
into the document. `SafeXml.parse(String xml)` reads the string into a DOM `Document` with
that door shut: the external subset a document names is never fetched and an external entity
is never resolved, so neither can exfiltrate a file or reach a host. A DTD is still allowed,
internal entity declarations included, because PubMed and NCBI responses carry one. The
PubMed and PubMed Central tools of [Literature search](../../../../../../../../nucleo-ext-lit/src/main/java/ai/redouble/nucleo/ext/lit/PACKAGE.md)
parse through it. Each call builds a new parser, so any thread may call it.

## Formats: counts and durations for people

`Formats` renders numbers and durations for log lines and for summaries a model reads.

- `compactNumber(Number)` - a count as a person skims it: below a thousand as is, thousands
  rounded to the nearest K below ten thousand and to the nearest ten K from there, millions
  rounded to the nearest M with grouping past a thousand M. A count that rounds to a thousand
  K is a million, so the shortest rendering always wins (`4K`, never `0K`; `1M`, never
  `1000K`).
- `compactDuration(long millis)` - a duration in its two largest units: `850ms`, `45s`,
  `2m 15s`, `1h 5m`.
- `elapsed(long millis)` - a duration in the largest units that matter: days hide seconds
  and milliseconds, hours hide milliseconds, more than five minutes hides milliseconds, and a
  unit with a zero count is left out; zero is `0 ms`.
- `elapsedSince(long startMillis)` - `elapsed` measured from a `System.currentTimeMillis()`
  reading.

## Temporals and Parsing: values a model spells its own way

A model asked for a date or a yes-or-no answer writes it in many forms. These two read the
common ones, and the JSON deserializers use them, which is why a `LocalDate` field accepts
"2026-09-22T00:00" and a `Boolean` field accepts "yes"
([Answers as Java objects](../harness/schema/PACKAGE.md)).

- `Temporals.parseLenient(String)` tries a fixed sequence of formats, covering offset ISO,
  the US date shapes, ISO and dotted dates, RFC 822, local date-times and the LDAP
  generalized-time form, and returns the first that fits as the temporal type that format
  produces (`LocalDate`, `LocalDateTime` or `ZonedDateTime`), or null when none does. Day and
  month names are read in English whatever the default locale.
- `Parsing.yesNo(Object)` reads Y, YES, T, TRUE, 1 as true and N, NO, F, FALSE, 0 as false,
  case insensitive, whitespace trimmed, through the value's text form; null for null and for
  anything else.

## Texts: cutting text with no boundary left

`Texts.splitAtWords(String, int maxChars)` cuts a text into pieces of at most `maxChars`
characters, each cut at the last space of its window when that space falls in the window's
second half, else at the limit; the pieces are trimmed and the whitespace between them
dropped. It is the last resort of a splitter whose paragraph and sentence boundaries have run
out, as the built-in summarization tool's is.

## Reflection: building the classes configuration names

A deployment names some of the runtime's parts by class in its settings: its configurator,
the models backend, the model picker, the credential store
([Settings and the configurator](../PACKAGE.md)). `Reflection` builds them.

- `forName(String)` - the class by binary name, through the class loader that loaded the
  runtime.
- `newInstance(String)` and `newInstance(Class)` - a new instance through the no-arg
  constructor, made accessible when it is not public.

A class that cannot be found or built throws `IllegalStateException` with the cause; there is
no fallback.
