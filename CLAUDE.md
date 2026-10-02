Read `AGENTS.md` in this directory and follow it. The rule about credentials in it is not
advisory: the human provides them, you never look for them, even if you know where they are.

Java files in this repository follow the Spring Framework file conventions, since Spring and
Hibernate are its audience's lineage:

```java
/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ...;

/**
 * Description...
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
```

- Every file starts with that license header, verbatim.
- Type-level javadoc tag order is Spring's: `@author`, `@since`, `@param`, `@see`,
  `@deprecated`.
- `@author` names humans only - never an AI co-author, in any spelling. The build
  workflow refuses a source file that names one, or that lacks the header.
- `@since` is `<version> (<date>)`: the release the type first ships in (currently `0.1`)
  and the date the file was created, which the version alone does not carry.
