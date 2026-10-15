# ADR-0001: Java 21 + Spring Boot 4.1 + JDA 5

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
Most Discord bots use Python or Node.js, which are lighter. The developer is specializing in Spring Boot backend at Riwi and wants the project to strengthen that profile.

## Options considered
| Option | Idle RAM (approx.) | Pros | Cons |
|---|---|---|---|
| Spring Boot + JDA (JVM) | 200-350 MB | Matches specialization, DI, strong portfolio | Higher memory, slower start |
| Plain JDA | 80-150 MB | Lighter | No Spring practice, manual wiring |
| Spring Boot native (GraalVM) | 50-100 MB | Instant start, low memory | Complex builds, JDA reflection issues |
| Node.js / Python | 50-80 MB | Common for bots | Does not build the target skill set |

## Decision
Java 21 + Spring Boot 4.1 + JDA 5 on a regular JVM.

## Consequences
- Virtual threads fit an I/O-bound bot well.
- Keep memory down: minimal starters, no web server initially, `-Xmx512m`.
- GraalVM native image is a possible later optimization.

## Amendment (2026-10-15)
Spring Boot 3.x reached end of OSS support on 2026-06-30 (3.5 was the last 3.x line). The project starts on Spring Boot 4.1 (Spring Framework 7). Java 21 remains supported. Note the 4.x module split (e.g. `RestClient` auto-configuration lives in its own module); follow the Spring Boot 4.0 migration notes when something from 3.x tutorials does not resolve.
