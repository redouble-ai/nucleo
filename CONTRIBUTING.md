# Contributing

Nucleo is developed by Redouble AI, which keeps its design coherent from one release to
the next. Opinions and suggestions are welcome, well-argued ones especially, and the place
for them is an issue, where the approach is agreed before anybody writes code. Contributions
are taken in the forms below, and the categories are stated here so that nobody writes
code the project will not take.

## Bug reports and questions: always welcome

Open an issue. Include the version, the minimal case that reproduces the behaviour, what
you expected and what happened. If you are not sure whether something is a bug or the
design, ask: the answer is useful either way, and it often ends up in the documentation.

## Small fixes: send them directly

Open a pull request, no issue needed:

- typos and documentation corrections
- obviously wrong behaviour with an obvious fix
- broken or flaky tests
- incorrect javadoc, wrong examples, stale references

Keep the change to the thing being fixed. It is merged on a green build.

## Changes that start as an issue

Open an issue and agree the approach before writing code:

- anything that changes public API
- anything that adds a dependency
- new features
- moving or reorganising packages

The issue is where the design is proposed, in words, and the code follows what was agreed
there. A pull request in this category that arrives without an agreed issue is closed
unreviewed, with the category named.

## What is not taken

- large refactors and architectural changes
- a redesign that arrives as a pull request, generated or written by hand

A reported fix may be implemented by the authors rather than merged as a patch, in the
core runtime in particular. The report is what matters, and the fix credits the reporter,
in the changelog and the commit, unless they prefer otherwise.

## Sign-off

Contributions come in under the Developer Certificate of Origin. There is no CLA to sign
and no account to create. Add a sign-off line to your commits:

    git commit -s

That appends `Signed-off-by: Your Name <your@email>`, which certifies that you wrote the
patch or otherwise have the right to submit it under this project's licence. The full text
is in [DCO](DCO), and the build checks every commit of a pull request for the line.

Contributions are licensed under Apache 2.0, the project's licence, per section 5 of that
licence.

## A person is responsible for every line

Use whatever tools you write with, coding agents included: the repository carries its own
instructions for them in [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md). Authorship is
a different matter. A contribution is submitted by a person who has read it, understands
it and answers for it, and that is the person it is attributed to. An `@author` tag, a
commit author or a sign-off naming an AI, a coding agent or a tool is refused, and the
build checks the sources for one. The sign-off below certifies that a person stands
behind the contribution, and a tool cannot certify anything.

## Building and testing

Java 25 and Maven. `mvn install` builds every module and runs the tests; `mvn install
-DskipTests` builds without them; `mvn test -pl nucleo-core` runs one module's tests. A
pull request builds and passes its tests with no credentials, no network access and no
database, which is how the build workflow runs it.

Java sources follow the Spring Framework file conventions described in [CLAUDE.md](CLAUDE.md):
the license header at the top of every file, and `@author` naming the person responsible.

## Response

Issues and pull requests are triaged weekly. A pull request that has had no response after
two weeks gets one when you comment on it. A closed pull request is told which category
it fell into: a fast no over a slow maybe.
