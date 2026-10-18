# Plan de implementación 017 - 029 (y las tareas que dependen de ellas)

Guía operativa para el dueño del repo: qué ejecutar en paralelo, qué esperar y en qué orden mergear. Estado al 2026-10-18. Cubre las tareas pendientes 010, 017, 018, 020, 021 y las nuevas 029-037 (mejora del coach de `/analyze` y despliegue). Fuente de decisiones: `docs/PROJECT_CONTEXT.md`, `docs/implementation-plan.md`, `docs/features.md` y la evidencia `docs/evidence/029-coaching-analytics/`.

## Tareas

| # | Tarea | Depende de | Spec | Implementa |
|---|---|---|---|---|
| 017 | Web report (Flyway V4, `/r/{publicId}`, botón en el embed) | 009, 019 (mergeadas) | **Falta escribir** | Claude Code |
| 018 | Soul curve en la web, dominio y HTTPS | 017 | Falta escribir | Antigravity |
| 020 | Check del rol de Patreon (reemplaza el `PremiumChecker` por defecto) | 019 | **Falta escribir** | Antigravity |
| 021a | `/items` premium (primera mitad de la antigua 021) | 007, 019 | **Falta escribir** | Claude Code |
| 021b | `/compare` premium | 007, 019, 029 | Falta escribir | Claude Code |
| 029 | Base del coach: telemetría con muertes y detalle, curva de rendimiento, `CoachInsightProvider` | 009, 022, 023 | `docs/tasks/029-coach-foundation.md` **lista** | Antigravity |
| 030 | Limpiar `}` y `,` sobrantes del texto de la IA | 008, 009 | `docs/tasks/030-analysis-text-cleanup.md` **lista** | Antigravity |
| 031 | Coach: fases y z-score contra la curva | 029 | Falta escribir | Antigravity |
| 032 | Coach: línea de muertes (fase, tiempo muerto, asesino, repetición) | 029 | Falta escribir | Antigravity |
| 033 | Coach: build vs meta (`item-flow-stats`) | 029 | Falta escribir | Antigravity |
| 034 | Coach: forma reciente (últimos N partidos del héroe) | 029 | Falta escribir | Antigravity |
| 035 | Coach: matchup y rival de lane (`assigned_lane`, `hero-counter-stats`) | 029 | Falta escribir | Antigravity |
| 036 | Despliegue: Dockerfile, compose de producción, CI, fuentes para el gráfico | ninguna | **Falta escribir** | Antigravity |
| 037 | Prompt v2, esquema con prioridades y severidad, embed y web | 031-035 | Falta escribir | Claude Code |
| 010 | Tuning del prompt con partidos reales de testers | 037, 036 | Falta escribir | Dueño + Claude |

Nota: la 021 original se divide en 021a y 021b (aprobado por el dueño). Los números 031-037 son una propuesta mínima; el alcance exacto se fija al escribir cada spec.

## Olas de ejecución

### Ola 1: todas en paralelo, sin depender entre sí
Tareas: **029, 030**, 017, 020, 021a, 036.

- Solo 029 y 030 tienen spec hoy. Las otras cuatro necesitan su spec antes de crear el worktree.
- Cada una en su propio worktree y rama `feature/NNN-slug`, creados desde `origin/main` (skill `task-worktree`).
- Solo 017 crea una migración Flyway (V4). Nadie más debe crear migraciones en esta ola.
- Todas las tareas añaden claves al **final** de `messages.properties`: si dos se mergean, el conflicto es de texto y se resuelve conservando ambos bloques.

### Ola 2: esperar a que 029 esté mergeada en `main`
Tareas: 031, 032, 033, 034, 035 y 021b, más 018 cuando 017 esté mergeada.

- Crear estos worktrees **después** del merge de 029, para que partan de un `main` que ya tiene `CoachInsightProvider`.
- Cada tarea del coach (031-035) añade **una clase nueva** que implementa `CoachInsightProvider`. Casi no comparten archivos.
- Puntos de choque conocidos: `CacheConfig` y el test `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign` (cada tarea que añade una caché lo toca) y `DeadlockApiClient`. Si dos tareas añaden caché, la segunda en mergear resuelve el conflicto conservando ambas.

### Ola 3: secuencial
1. **037** cuando 031-035 estén mergeadas (o al menos las que se quieran incluir).
2. **010** con testers, cuando 037 esté mergeada y el bot corra desplegado (036).

## Orden de merge recomendado

| Paso | Merge | Por qué |
|---|---|---|
| 1 | **030** | Cambio mínimo, sin conflictos, arregla texto visible hoy. |
| 2 | **029** | Desbloquea toda la ola 2. Mergear lo antes posible. |
| 3 | 036, 020, 021a, 017 (cualquier orden) | Independientes entre sí. Antes de mergear cada una, hacer `git merge origin/main` en su rama y pasar `verify` de nuevo. |
| 4 | 031-035, 021b, 018 (cualquier orden) | Tras el paso 2 (y 017 para 018). Misma regla: actualizar con `main` antes de mergear. |
| 5 | 037 | Cuando estén las capas del coach. |
| 6 | 010 | Es trabajo con testers, no tiene rama obligatoria. |

Regla general: mergea una a una, y tras cada merge las ramas abiertas deben traer `main` y repetir `./mvnw clean verify` antes de su propio merge. `docs/PROJECT_CONTEXT.md` se actualiza **después** de mergear un lote (AGENTS.md prohíbe editarlo desde ramas paralelas): cada tarea deja su reporte en `docs/task-reports/` y marca el contexto como pendiente.

## Qué NO ejecutar todavía
- 031-035, 021b, 018: esperan a 029 (y 017 en el caso de 018).
- 037: espera a las capas 031-035.
- 010: espera a 037 y al despliegue.
- 017, 020, 021a, 036: no se pueden lanzar hasta que exista su spec (siguiente paso de planificación).

## Zonas de conflicto entre tareas

| Archivo | Tareas que lo tocan | Cómo resolverlo |
|---|---|---|
| `messages.properties` | casi todas (claves al final) | Conservar ambos bloques |
| `CacheConfig`, `DeadlockApiClientCachingTest` | 029, 033, 034, 035, 021a, 021b | Conservar todas las cachés y la lista de nombres/TTL en el test |
| `AnalyzeService` | 029 (constructor y ruta fresca), 017 (posible) | Revisar a mano; no aceptar una sola versión a ciegas |
| `AnalysisInput` / `AnalysisResult` | 029, 030, 037 | 030 solo añade un constructor compacto a `AnalysisResult`; 037 es la única que cambia el esquema |
| `docs/PROJECT_CONTEXT.md` | todas | Actualizar tras el merge de cada lote, nunca desde varias ramas |

## Hechos verificados en la evidencia (base de las tareas del coach)
- `GET /v1/matches/{id}/metadata` trae por jugador: `hero_id`, `team`, `assigned_lane`, `player_slot`, `death_details[]` (tiempo, asesino, tiempo muerto, posición) y `stats[]` con `creep_kills`, `possible_creeps`, `shots_hit/missed`, `player_damage_taken`, `gold_death_loss`, etc.
- `assigned_lane` vale 1, 4 o 6 en el partido auditado (el README de la evidencia dice "1 a 4", no es exacto). Hay tres lanes con dos jugadores por equipo.
- `GET /v1/analytics/player-performance-curve` con `resolution=0` devuelve 16 puntos de tiempo absoluto (180 a 2400 s) con `_avg` y `_std` de net worth, kills, deaths, assists, daño, denies y más. No hay percentiles; el benchmark es media y desviación.
- `hero-counter-stats` devuelve 42 filas para Lash (héroe 31).
- Calidad de los 4 análisis reales: sin alucinaciones de números, pero con errores de razonamiento (compara mal un valor con su mediana, elige como "fuga" un deny en una partida dominada) y caracteres JSON sobrantes en `next_game_action`. El defecto de razonamiento se ataca en 031-035 (datos) y 037 (priorización), no solo con el prompt.

## Limitaciones conocidas del plan
- Los datos nuevos de telemetría (muertes, slot, lane, detalle por snapshot) solo existen cuando la fuente es el JSON. El adaptador raw (fallback) no los trae porque sus números de campo protobuf no están verificados. Las capas del coach deben tratar esa ausencia como "sin datos".
- No hay mapa de zonas, así que no se puede afirmar "murió en territorio enemigo" con la información actual.
- No está verificada la ventana temporal por defecto de `player-performance-curve`.
- `max_tokens` (hoy 1500) se revisa en 037; sube a unos 2500 si el esquema v2 lo necesita.

## Cómo lanzar cada tarea (cuando haya spec)
1. Mergear a `main` la rama con estos specs y este plan (los worktrees parten de `origin/main`; si el spec no está en `main`, el worktree no lo tendrá).
2. `task-worktree NNN` crea `../rejuv-wt/NNN` en la rama `feature/NNN-slug`.
3. Abrir una terminal en ese worktree, lanzar `agy` y pasarle `/execute-task NNN`.
4. Al terminar, revisar el diff (`review-task-diff`), empujar la rama y abrir el PR. Nada se empuja ni se mergea automáticamente.
