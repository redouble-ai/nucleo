# Security

Nucleo runs agents inside your application's process and security perimeter, so a defect
in it is a defect inside that perimeter. Report it to the authors before anyone else.

## Reporting

Write to oss@redouble.ai. Do not open a public issue for a vulnerability.

Include what you found, how to reproduce it, the version, and the impact as you see it.
The report is acknowledged within two working days with what is being done about it, and
the fix credits you unless you ask otherwise.

There is no bounty programme.

## What counts

Anything that lets a model, a tool result or a document the runtime reads do what the
application's code did not permit: widen a scope, bypass a guardrail, reach a tool or a
credential it was not given, alter an artifact in transit, or read another job's data.
Also anything in the providers that exposes a credential, in logs included.

A model producing a wrong or harmful answer within the permissions the application gave it
is not a vulnerability in the runtime; that is what guardrails and scopes are for, and a
weakness in those is.

## Supported versions

The current release, 0.1, and the `main` branch. A fix ships as a new release on Maven
Central; nothing published there is ever changed in place.
