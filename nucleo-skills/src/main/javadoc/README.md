# nucleo-skills

This artifact carries no Java code, so it has no javadoc. It ships agent skill bundles under
`META-INF/skills/`, each a directory with a `SKILL.md` that states the skill's name,
description, allowed tools and instructions. `SkillJarsLoader` in `nucleo-core` discovers
them on the classpath and registers them; the documentation of the runtime describes the
format and the loader.
