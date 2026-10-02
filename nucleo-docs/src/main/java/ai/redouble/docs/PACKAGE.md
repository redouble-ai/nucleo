# Package: ai.redouble.docs

This module turns the markdown files of the repository into the documentation site you are
reading. The pages are the `PACKAGE.md` beside each package and a few other documents; the
order they are read in, and the title each carries, come from one file,
`nucleo-docs/CONTENTS.md`. The generator also proves the code shown on every page is the
code that compiles. This page is for people who write or change documentation pages: it
states the rules a page must follow for the site to build.

## Where the site is built

The site is built in two places, from the same sources:

- **The full site**, with javadoc, by `mvn site` from the reactor root. The aggregate
  javadoc runs at pre-site (bound in the reactor root's pom), then this module's site phase
  runs `GenerateDocs`, which writes the site under `src/main/docs`. Build the reactor before
  running `mvn site`; the site lifecycle does not compile.
- **The copy inside the demo.** nucleo-demo-engine runs the same main at generate-resources
  with `--out` pointed into its jar (`META-INF/resources/docs`, served at `/docs` by both demo
  hosts), `--no-javadoc-links`, since the demo does not carry the javadoc tree, and
  `--no-versions`, since the copy stands alone instead of beside other versions. The
  embedded copy is generated from the same sources as the binary, so it can never be stale.

The whole pipeline is Maven plus a JDK, with no shell and no external tools, so it runs the
same on any contributor's machine.

## Where the site is published

The published documentation lives in a second repository, the documentation repository.
It holds every published version side by side, already generated, one directory per
version under `site/nucleo`, and the documentation host serves its `site` directory as it
stands. A version is published by a person, on purpose, from a checkout of the sources at
that version; no build publishes on its own.

`mvn site-deploy -Ddocs.repo=<checkout of the documentation repository>` from the reactor
root builds the full site as `mvn site` does and then runs `PublishDocs`, which puts the
site into that checkout under the reactor's version. `PublishDocsTest` holds what follows.

- A version that is not in the repository yet is added as a directory of its own. A
  version that is there already has its directory replaced whole, so a page the new
  publication no longer has does not survive. Other versions are not touched.
- The site's own `.gitignore`, which keeps generated files out of the source repository,
  is not copied.
- `site/nucleo/versions.json` is written again from the directories: the published
  versions, newest first. Numbers compare as numbers (0.10.0 is newer than 0.9), and a
  version with a qualifier comes before the one it leads to (1.0.0-RC1 before 1.0.0).
- `site/_redirects`, the host's routing file, is written again: the site's root and
  `/nucleo` lead to `/nucleo/current/`, and every address under `/nucleo/current/` is
  served by the newest version, whichever version was just published. `/llms.txt` and
  `/llms-full.txt`, where a coding agent looks for them, lead to the newest version's.
- `site/robots.txt` is written again: every version's own directory is kept out of
  search engines and `/nucleo/current/` stays open, so a page is indexed once, under the
  address that always shows the newest version.
- `site/404.html`, the page the host shows for an address that leads nowhere, is written
  with them; it leads to `/nucleo/current/`.
- The repository's own `LICENSE` and `NOTICE` are those of the newest version: publishing
  the newest version writes them, publishing an older one leaves them alone.
- A site directory with no `index.html`, a repository checkout with no
  `site/nucleo/versions.json`, a version that is not numbers separated by dots with an
  optional qualifier, and a directory among the versions that is not named as one are
  each refused, and the refusal says what was expected.
- Nothing is committed or pushed. The checkout is left changed for a person to review,
  commit and push; the push is the publication.

## What is here

| Type | Role |
|---|---|
| `GenerateDocs` | The main. Reads the contents, builds the class index, checks the samples, renders markdown through flexmark, wraps each page in the shared frame, writes the llms.txt pair and the search index, verifies the result. |
| `SearchIndex` | What the search box searches: one entry per page opening, per section and, with the javadoc tree, per public class, written as `search-index.js`. |
| `Contents` | The reading order, parsed from `nucleo-docs/CONTENTS.md`: the site's title and introduction, the front matter, and the parts with their introductions and pages. |
| `Samples` | Checks every checked sample of a page against the source region it names. |
| `LinkRewriter` | Resolves inter-doc `.md` links against the page manifest and turns backticked class names into javadoc links. |
| `NavGenerator` | Emits the sidebar shared by every page from the contents, the version badge at its top. |
| `PublishDocs` | The main of publication. Puts the generated site into a checkout of the documentation repository as one version, and rewrites the list of versions and the routing file. |

## The rules the generator holds

`GenerateDocsTest` holds this section.

### The reading order

- The site is a story, and `nucleo-docs/CONTENTS.md` is its order: its H1 is the site's
  title, the paragraph under it the introduction, the list above the first `##` heading
  the front matter, and each `##` heading a part with an introduction and a list of
  pages. An item is exactly `- [Title](relative/path.md)`, nested under the item above by
  two spaces; the title is what the site calls the page. Any other list item, a link to a
  missing file and a file listed twice are refused.
- Every `PACKAGE.md` under any module's `src/main/java` has a place in the contents; one
  that is not listed fails the build, naming it. The contents must list the reactor's
  `README.md`, which is the site's index page.

### Pages

- A page id, which is the page's file name in the site, is the package path with the
  `ai/redouble/nucleo` prefix stripped (`ai/redouble` for a package outside Nucleo, like the
  demo's) and slashes turned into dashes for a `PACKAGE.md`, `index` for the README, and the
  file name lowercased with underscores turned into dashes for any other document; two pages
  with one id fail the build.
- Every page opens with its H1, which the page frame replaces: the part it belongs to
  ("Part 6 · Hundreds of agents in one JVM"), the title from the contents, and the package
  and module a source under a module's tree documents. Below the body, the previous and
  next pages of the reading order link on across parts. The sidebar is the front matter
  and one numbered section per part, the current page's part expanded.
- A blockquote that opens with **Example** is a topic's pointer to its example and renders
  as a highlighted callout: `> **Example:** [Title](path/PACKAGE.md) - what it shows.`

### Code shown on a page

- A fenced block directly after `<!-- sample: path/To.java#name -->` is a checked sample:
  it must equal the lines between `// region name` and `// endregion` in that file, the
  path relative to the markdown, ignoring the region's common indentation and trailing
  whitespace. The code reads whole in the tree and the build proves it current: a missing
  file or region, a directive with no fence under it, or a fence that differs fails the
  site with the region's text as it stands.

### Links

- A markdown link that resolves to a manifest page is rewritten to the generated page,
  and a link whose text is itself a path gets the page's title as its text; a link that
  resolves nowhere stays as written and warns.
- A link that starts inside a code span or a fence is code and stays as written; a link
  whose text is code resolves like any other.
- Backticked code references link into the aggregate javadoc: class names to their pages,
  member references (`Foo.bar(...)`, `Foo#bar`) to the class page, package names to their
  summaries. Class names inside fenced code link too, client-side: the generator emits
  `class-index.js` and the page script wraps the tokens highlight.js marked as class names.
  Backticks and fences are the author's "this is code" signal and the only text these passes
  touch; prose mentions stay prose. All of it no-ops in a `--no-javadoc-links` build.

### Search

- Every page carries a search box in its titlebar. What it searches is `search-index.js`,
  which the generator writes beside the pages: one entry for each page's opening, found by
  the page's title, and one for each heading, found by the heading and landing on the
  heading's anchor. An entry's text is what a reader sees there, code and table cells
  included, with the markdown syntax and the sample directives gone.
- A build that ships the javadoc tree adds one entry per public class, found by the
  class's name and package and landing on its javadoc page; a `--no-javadoc-links` build
  has none.
- A page loads the index and the search library the first time its search box is used, so
  a page that is only read loads neither. The index is a script, which a page opened
  straight from disk can load where it could not fetch a data file.

### The version

- Every page carries the version the site was generated for, the reactor's version, as a
  badge beside the name at the top of the sidebar.
- A site published beside other versions names the list of them on the badge:
  `versions.json` in the directory above the site. The page reads that list and the badge
  becomes a choice among the published versions; choosing one opens the same page of that
  version, or its front page when that version has no such page.
- A `--no-versions` build stands alone: its badge names no list, so the page asks for
  none and the badge stays a badge.

### The license

- The site is a copy of the work, so it carries the work's license and notice: the
  reactor's `LICENSE` and `NOTICE`, as `LICENSE.txt` and `NOTICE.txt`.
- Every page ends with a footer that states whose work it is and links both files. The
  copyright line is the line of the `NOTICE` that opens with "Copyright"; a `NOTICE`
  without one fails the build.

### What a coding agent reads

- The site carries `llms.txt`, the contents in the llms.txt shape with every link pointing
  at the site's pages, and `llms-full.txt`, every page's markdown under its title in
  reading order, for a coding agent to read in one fetch.

### The look

- The stylesheet ships as this module's `style.css` resource - the Redouble theme the
  demo page carries. The javadoc's matching theme is separate
  (`src/main/javadoc/javadoc-theme.css` at the reactor root, applied by the javadoc
  plugin's `addStylesheets`).
