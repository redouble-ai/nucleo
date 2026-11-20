---
name: Advisory in a dependency
about: A published advisory names a library that a module of this repository resolves
labels: dependencies
---

This form is for an advisory that is already public and names a library Nucleo depends on.
A vulnerability in Nucleo's own code is reported privately; SECURITY.md says how.

**Version** (the `ai.redouble` artifact version, or the commit if built from source):

**Advisories**

One row per advisory.

| Library | Resolved version | Advisory | Severity | Fixed in |
|---|---|---|---|---|
| `group:artifact` | | CVE and GHSA ids | | |

**Where the library enters**

The dependency path from the module to the library, and what selects the version: the
library that asks for it, a platform BOM, or an entry in one of this repository's poms.

**Modules that resolve it**

Each module that resolves the affected version, and whether it is published to Maven
Central. A module resolved on its own and the application that includes it can resolve
different versions; say which one was scanned.

**Who is exposed**

An application that depends on a published artifact, a person who runs a demo from a
checkout, or nobody outside the build.

**The fix**

The version that closes the advisory and where it is set. A version a module manages stays
in that module; one it declares travels to whatever depends on it.

**How it was checked**

The scanner and what it was run against, before and after.
