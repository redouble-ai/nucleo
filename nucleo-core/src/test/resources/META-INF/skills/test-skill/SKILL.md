---
name: test-skill
description: A fixture skill used by SkillJarsLoaderTest to verify classpath discovery and parsing.
license: Apache-2.0
allowed-tools:
  - get_artifact_field
  - search_supporting_documents
metadata:
  author: Redouble AI Tests
  bundle_id: ai.redouble.test.test-skill
  trigger_keyword: test
---
You are a test skill body. The loader is expected to capture this text verbatim, trim the
preceding frontmatter, and wrap it in a `Prompt` accessible via `Skill.body()`.

The test suite asserts:
- name matches the directory and the frontmatter
- description matches the frontmatter
- body equals the text after the closing `---`
- allowedTools matches the YAML list
- metadata carries author / license / bundle_id / trigger_keyword
- resources includes the sibling file under `templates/`
