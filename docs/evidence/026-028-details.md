# Evidencia técnica para las Tareas 026 y 028: Nombres de Jugadores, Assets de Ítems y Telemetría para Gráficos

Documento de evidencia técnica previa para la formulación de las especificaciones de las Tareas 026 y 028 de Rejuv.
Cada sección clasifica la información explícitamente en:
- **Verificado**: Hecho comprobado directamente en el repositorio local, en la especificación OpenAPI upstream (`https://api.deadlock-api.com/openapi.json`) o en llamadas HTTP reales secuenciales controladas (con fuentes exactas: archivos, líneas, endpoints, cabeceras y respuestas).
- **Supuesto**: Inferencia lógica fundada en la evidencia recopilada, pendiente de prueba exhaustiva.
- **Falta evidencia**: Aspectos que no pudieron confirmarse con las fuentes disponibles y que requieren validación adicional.

---

## Pregunta A: Obtención de nombres de jugador por `account_id`

### Verificado

#### 1. Endpoint y contrato según OpenAPI v3.1.0
- **Fuente**: `https://api.deadlock-api.com/openapi.json` (tags: `["Steam"]`, operationId: `steam`).
- **Ruta y método**: `GET /v1/players/steam` (Summary: *"Batch Steam Profile"*).
- **Parámetros**:
  - `account_ids` (en query, **requerido**): Array de enteros `int64` (mínimo 0), `minItems: 1`, `maxItems: 1000`. Formato en la URL: lista de IDs separados por coma (`?account_ids=139205933,1000001`). La documentación de OpenAPI indica expresamente: *"Comma separated list of account ids, in SteamID3 or SteamID64 format."*
  - `refresh` (en query, **opcional**): `boolean`. Si es `true`, fuerza una sincronización en vivo contra la Steam Web API (`GetPlayerSummaries` + `GetFriendList`) antes de responder, persistiendo los datos actualizados en la tabla `steam_profiles` del backend. Si es `false` u omitido, lee directamente de la base de datos de deadlock-api. Cuando `refresh=true`, la consulta está limitada a un máximo de 100 cuentas por petición.
- **Content-Type de respuesta**: `application/json`.
- **Esquema de respuesta (200 OK)**: Retorna un array de objetos con esquema `#/components/schemas/SteamProfile`:
  - `account_id` (`integer`, format: `int32`, `>= 0`): Identificador de cuenta de Steam (SteamID3).
  - `personaname` (`string`): **Nombre público de Steam del jugador**.
  - `profileurl` (`string`): URL completa del perfil comunitario de Steam (e.g. `https://steamcommunity.com/profiles/76561198099471661/`).
  - `avatar` (`string`): URL de la imagen de avatar en resolución 32x32 píxeles.
  - `avatarmedium` (`string`): URL de la imagen de avatar en resolución 64x64 píxeles.
  - `avatarfull` (`string`): URL de la imagen de avatar en resolución 184x184 píxeles.
  - `last_updated` (`string`, format: `date-time`): Timestamp ISO-8601 de la última actualización del perfil.
  - `friends` (`array` de `SteamFriend`): Lista de amigos con `account_id` y timestamp `friend_since`.
  - `matches_played_last_30d` (`integer`, format: `int64`, `>= 0`): Partidas jugadas en los últimos 30 días.
  - `countrycode` (`string | null`): Código de país ISO de 2 letras (e.g. `"US"`).
  - `last_team_avg_badge` (`integer | null`, format: `int32`, `>= 0`): Medalla/rango promedio del último equipo registrado.
  - `realname` (`string | null`): Nombre real del perfil de Steam (si el usuario lo configuró como público).
- **Códigos de respuesta HTTP definidos en OpenAPI**:
  - `200`: "Steam Profiles" (array de `SteamProfile`).
  - `400`: "Provided parameters are invalid."
  - `404`: "No Steam profile found." (se emite si ninguna de las cuentas solicitadas existe en el backend).
  - `429`: "Rate limit exceeded (only enforced when refresh=true)."
  - `500`: "Failed to fetch steam profiles."
  - `502`: "Steam Web API call failed (only when refresh=true)."
- **Límites de tasa (Rate Limits) documentados en OpenAPI**:
  - **Ruta de lectura estándar (`refresh=false` o no enviado)**:
    - Por IP: `100 req/s`.
    - Con API Key: Sin límite restrictivo documentado (opera sobre el pool general de base de datos).
    - Global: Sin límite restrictivo documentado.
  - **Ruta con refresco forzado (`refresh=true`)**:
    - Por IP: `3 req/min` + `15 req/h` (máximo 100 account IDs por petición).
    - Con API Key: `10 req/min` + `60 req/h`.
    - Global: `30 req/min` + `200 req/h`.
- **Mecanismo de Load Shedding (HTTP 503) y Retry-After**:
  - Documentado en la sección global del OpenAPI (`spec.info.description`): los endpoints respaldados por base de datos encolan peticiones cuando el backend está bajo carga. Si una consulta espera demasiado, el servidor retorna `503 Service Unavailable` con una cabecera `Retry-After: <segundos>`.
  - **Implementación actual en Rejuv**: En `src/main/java/sntiago05/rejuv/clients/DeadlockApiRequests.java` (líneas 136-152), el framework base del cliente ya intercepta las respuestas 503, lee `Retry-After`, verifica si el tiempo de espera no excede `deadlock-api.max-retry-after` (10 segundos por defecto) y efectúa exactamente **un reintento** tras la espera. Si falla nuevamente, lanza `DeadlockApiUnavailableException`.

#### 2. Comportamiento en llamadas reales secuenciales
- **Llamada 1 (Cuenta individual)**:
  - Petición: `GET https://api.deadlock-api.com/v1/players/steam?account_ids=139205933` (HTTP/1.1, User-Agent: `Rejuv-Evidence/1.0`).
  - Resultado: `HTTP 200 OK`.
  - Cabeceras: `content-type: application/json`, `cache-control: public, max-age=60`, `cf-cache-status: HIT`, `via: 1.1 Caddy`, `server: cloudflare`.
  - Payload retornado: Array de 1 elemento:
    ```json
    [
      {
        "account_id": 139205933,
        "personaname": "stroodl",
        "profileurl": "https://steamcommunity.com/profiles/76561198099471661/",
        "avatar": "https://avatars.steamstatic.com/0ba457360301c8f09d01a46350c7b7e25260b31c.jpg",
        "avatarmedium": "https://avatars.steamstatic.com/0ba457360301c8f09d01a46350c7b7e25260b31c_medium.jpg",
        "avatarfull": "https://avatars.steamstatic.com/0ba457360301c8f09d01a46350c7b7e25260b31c_full.jpg",
        "realname": "Jason",
        "countrycode": "US",
        "last_updated": "2026-10-13T21:36:57Z",
        "friends": [ ... 206 elementos ... ],
        "matches_played_last_30d": 191,
        "last_team_avg_badge": 0
      }
    ]
    ```
- **Llamada 2 (Batch con cuenta existente y cuenta inexistente)**:
  - Petición: `GET https://api.deadlock-api.com/v1/players/steam?account_ids=139205933,999999999`.
  - Resultado: `HTTP 200 OK`.
  - Payload retornado: Array con **1 solo elemento** (`account_id: 139205933`, `personaname: "stroodl"`). La cuenta no encontrada `999999999` fue **omitida silenciosamente**, sin arrojar error HTTP 404 ni abortar el resto del batch.

#### 3. Estado de los DTOs actuales en Rejuv
Inspección completa de los DTOs en `src/main/java/sntiago05/rejuv/clients/dto/`:
- **DTOs asociados a jugadores**:
  - `AccountRank.java` (líneas 7-10): campos `account_id`, `badge`, `rank`, `subrank`.
  - `MatchHistoryEntry.java` (líneas 7-22): métricas de partida (`match_id`, `hero_id`, `player_kills`, `net_worth`, etc.).
  - `MatchMetadataResponse.java` (`Player`, líneas 25-32): campos `account_id`, `team`, `hero_id`, `items`, `stats`, `power_up_buffs`.
  - `MateStat.java` (líneas 7-11): campos `mate_id`, `matches_played`, `wins`.
  - `PlayerHeroStat.java` (líneas 7-12): campos `hero_id`, `matches_played`, `wins`, `kills`, `deaths`, `assists`.
  - `PlayerRank.java` (líneas 7-10): campos `badge`, `rank`, `subrank`.
- **DTOs asociados a assets del juego (no jugadores)**:
  - `HeroAsset.java` (líneas 7-10): `id`, `class_name`, `name`, `images`.
  - `ItemAsset.java` (líneas 7-14): `id`, `class_name`, `name`, `type`, `item_tier`, `item_slot_type`, `cost`, `image`.
  - `RankAsset.java` (líneas 7-10): `tier`, `name`, `images`.
- **Conclusión comprobada**: **Ningún DTO actual en Rejuv contiene nombres de jugadores.** Todas las clases y records del sistema que representan jugadores se basan exclusivamente en el `account_id` numérico.

### Supuesto
- Para las tareas 026 y 028, las consultas a `/v1/players/steam` deben realizarse siempre con `refresh=false` (o sin el parámetro `refresh`) para operar bajo el límite holgado de `100 req/s` de lectura en base de datos local y evitar consumir la estricta cuota de la Steam Web API (`3 req/min` / `15 req/h`), la cual además provocaría errores 429 bajo concurrencia.
- Dado que el endpoint ignora y omite los IDs no registrados en lugar de devolver placeholders, el cliente de Rejuv deberá mapear el array retornado a un mapa `Map<Long, SteamProfile>` (o `Map<Long, String>` para nombres) y manejar los `account_id` faltantes mediante un fallback apropiado (e.g. mostrar el `account_id` formateado como string).

### Falta evidencia
- No se comprobó en vivo la respuesta del endpoint cuando **ninguna** de las cuentas del parámetro `account_ids` existe en la base de datos de deadlock-api (OpenAPI estipula HTTP 404 `"No Steam profile found."`, pero no se consumió una llamada HTTP para confirmar si retorna 404 o una lista vacía `[]`).
- No se ha verificado si perfiles de Steam configurados en modo 100% privado omiten el campo `personaname` o si Steam siempre expone el nombre público independientemente de la privacidad del inventario/perfil.

---

## Pregunta B: Assets de Ítems y Telemetría para Gráficos

### Verificado

#### 1. Campos del endpoint de assets de ítems en OpenAPI v3.1.0 y respuesta real
- **Fuente**: `https://api.deadlock-api.com/openapi.json` (tags: `["Items"]`, operationId: `get_item` y `get_items`).
- **Endpoints disponibles**:
  - `GET /v1/assets/items`: Catálogo completo de ítems, habilidades y armas (764+ entradas, ~8 MB).
  - `GET /v1/assets/items/{id_or_class_name}`: Consulta individual por `id` numérico o `class_name`.
- **Polimorfismo del esquema `Item`**: El esquema `#/components/schemas/Item` es un `oneOf` compuesto por:
  - `#/components/schemas/Upgrade` (ítems de tienda / mejoras).
  - `#/components/schemas/Ability` (habilidades activas y pasivas de héroes).
  - `#/components/schemas/Weapon` (armas base de héroes).
- **Campos específicos comprobados en `Upgrade` (ítems comprables)**:
  - **Nombre**:
    - `name` (`string`): Nombre legible y traducido del ítem (e.g. `"Extended Magazine"`).
    - `class_name` (`string`): Identificador interno del motor Source 2 (e.g. `"upgrade_clip_size"`).
  - **Icono / Imágenes**:
    - `image` (`string | null`): URL pública del icono en formato PNG (e.g. `"https://assets-bucket.deadlock-api.com/assets-api-res/images/upgrades/mods_weapon/clip_size.png"`).
    - `image_webp` (`string | null`): URL del icono en formato WebP optimizado (e.g. `"https://assets-bucket.deadlock-api.com/assets-api-res/images/upgrades/mods_weapon/clip_size.webp"`).
    - `shop_image` (`string | null`): URL de la ilustración de tienda en PNG (e.g. `"https://assets-bucket.deadlock-api.com/assets-api-res/images/items/weapon/basic_magazine.png"`).
    - `shop_image_webp` (`string | null`): URL de la ilustración de tienda en WebP.
    - `shop_image_small` (`string | null`): Variante en miniatura PNG (en la llamada real resultó `null`).
    - `shop_image_small_webp` (`string | null`): Variante en miniatura WebP.
  - **Costo**:
    - `cost` (`integer | null`, format: `int32`): Precio de compra en almas (e.g. `800`, `1250`, `3000`, `6200`).
  - **Otros campos estructurales en `Upgrade`**:
    - `id` (`integer`): Identificador numérico único del ítem.
    - `item_tier` (`integer`): Nivel del ítem (1, 2, 3, 4 o 5).
    - `item_slot_type` (`string`): Categoría del ítem (`"weapon"`, `"vitality"`, `"spirit"`).
    - `type` (`string`): Siempre `"upgrade"`.
    - `shopable` (`boolean`): Indica si el ítem está disponible para compra normal en la tienda (`true`).
    - `is_active_item` (`boolean`): Indica si otorga una habilidad activa mediante tecla asignada.
    - `activation` (`string`): Tipo de activación (e.g. pasiva, activa instantánea, canalizada).
- **Llamada 3 real**:
  - Petición: `GET https://api.deadlock-api.com/v1/assets/items/1548066885`.
  - Resultado: `HTTP 200 OK`.
  - Cabeceras: `cf-cache-status: EXPIRED`, `via: 1.1 Caddy`.
  - Valores confirmados: `id: 1548066885`, `class_name: "upgrade_clip_size"`, `name: "Extended Magazine"`, `type: "upgrade"`, `cost: 800`, `item_tier: 1`, `item_slot_type: "weapon"`, `image: "https://assets-bucket.deadlock-api.com/assets-api-res/images/upgrades/mods_weapon/clip_size.png"`, `image_webp: "https://assets-bucket.deadlock-api.com/assets-api-res/images/upgrades/mods_weapon/clip_size.webp"`, `shop_image: "https://assets-bucket.deadlock-api.com/assets-api-res/images/items/weapon/basic_magazine.png"`, `shopable: true`.

#### 2. Uso actual de `ItemAsset` y `GameAssets` en el código de Rejuv
- **`ItemAsset`** (`src/main/java/sntiago05/rejuv/clients/dto/ItemAsset.java`, líneas 6-19):
  - Record de Java con `@JsonIgnoreProperties(ignoreUnknown = true)` que mapea exclusivamente 8 campos:
    ```java
    public record ItemAsset(@JsonProperty("id") long id,
                             @JsonProperty("class_name") String className,
                             @JsonProperty("name") String name,
                             @JsonProperty("type") String type,
                             @JsonProperty("item_tier") Integer itemTier,
                             @JsonProperty("item_slot_type") String itemSlotType,
                             @JsonProperty("cost") Integer cost,
                             @JsonProperty("image") String image)
    ```
  - **Campos no consumidos actualmente en `ItemAsset`**: `image_webp`, `shop_image`, `shop_image_webp`, `shop_image_small`, `shopable`, `is_active_item`, `description`, etc.
- **`GameAssets`** (`src/main/java/sntiago05/rejuv/services/GameAssets.java`, líneas 20-103):
  - Componente de Spring (`@Component`) que indexa en memoria las listas de assets cacheadas en `DeadlockApiClient`:
    - `itemsById()` (líneas 43-45): Retorna `Map<Long, ItemAsset>` con todos los ítems indexados por su ID numérico.
    - `itemNames()` (líneas 48-50): Retorna `Map<Long, String>` con el nombre traducido del ítem (`item.name()`), descartando aquellos con nombre nulo.
  - La reconstrucción del índice se realiza únicamente cuando la referencia devuelta por `deadlockApiClient.getItems()` cambia (expiración de la caché de 24 horas).

#### 3. Campos de `MatchTelemetry` que sirven para gráficos
Estructura actual en `src/main/java/sntiago05/rejuv/domain/`:
1. **Nivel Partida (`MatchTelemetry.java`, línea 13)**:
   - `durationS` (`int`): Duración total de la partida en segundos. Define el límite máximo y la escala del **eje horizontal (tiempo)** para todas las series gráficas (`0 .. durationS`).
2. **Nivel Jugador (`PlayerTelemetry.java`, líneas 15-18)**:
   - `accountId` (`long`): Permite identificar y aislar las curvas del jugador analizado o comparar dos jugadores específicos.
   - `team` (`int`): Equipo (0 o 1). Permite agrupar las métricas de los 6 jugadores de cada bando para generar gráficos de **Ventaja Neta de Equipo (Team Net Worth Lead / Soul Difference Curve)** a lo largo del tiempo.
   - `heroId` (`int`): Permite asignar el nombre (`GameAssets.heroNames()`), icono (`HeroAsset.images()`) y color temático a la curva de cada jugador.
3. **Series Temporales periódicas (`StatSnapshot.java`, línea 11)**:
   - **`timeS` (`int`)**: Marca de tiempo en segundos (intervalos regulares tomados cada 180 s: 180, 360, 540, 720, 900, 1200, 1500, 1800, 2100... y el segundo final). Coordenada para el **Eje X**.
   - **`netWorth` (`long`)**: Almas acumuladas. Coordenada para el **Eje Y** en gráficos de progresión económica (*Soul Economy Curve / Net Worth over Time*).
   - **`playerDamage` (`long`)**: Daño total acumulado infligido a jugadores rivales. Coordenada para el **Eje Y** en gráficos de impacto de combate (*Damage Curve over Time*).
   - **`playerHealing` (`long`)**: Curación acumulada otorgada a aliados y propia. Coordenada para el **Eje Y** en gráficos de soporte (*Healing Curve over Time*).
4. **Hitos y Eventos Discretos (`ItemPurchase.java` y `PowerUpBuff.java`)**:
   - `ItemPurchase.boughtAtS` (`int`): Segundo exacto de adquisición del ítem. Permite graficar **marcadores de eventos / hitos (Item Timing Milestones)** sobre la curva de almas.
   - `ItemPurchase.soldAtS` (`Integer`): Segundo de venta del ítem (`null` si no se vendió). Permite graficar barras de intervalo de posesión (Gantt timeline) de ítems clave.
   - `PowerUpBuff.pickupTimesS` (`List<Integer>`): Segundos en que el jugador recogió buffs o runas. Permite renderizar pines de eventos en la línea de tiempo.

### Supuesto
- Para gráficos web o imágenes generadas (e.g. en la web pública de la Tarea 017 o en reportes gráficos de la Tarea 026/028), el formato WebP (`image_webp` o `shop_image_webp`) de los assets de ítems reducirá sustancialmente el ancho de banda respecto al PNG (`image`), aunque para Discord o librerías gráficas Java (como `Java2D` / `Graphics2D`) los archivos PNG estándar son más sencillos de decodificar sin dependencias adicionales nativas de WebP.
- La diferencia de almas entre equipos (`Team 0 Net Worth - Team 1 Net Worth`) calculada sumando los `netWorth` de los `snapshots` interpolados a cada minuto proporciona la curva clásica de ventaja/desventaja utilizada en la UI de repeticiones de Valve y en sitios analíticos de Deadlock.

### Falta evidencia
- El modelo actual de `StatSnapshot` en Rejuv descarta campos existentes en el JSON y Protobuf upstream (como `creep_kills` o desgloses de oro de objetivos). Si la Tarea 028 requiriera un gráfico de súbditos (CS / Minion farm over time), habría que comprobar si se autoriza ampliar `StatSnapshot` y el parser de `JsonMatchMetadataSource` / `FallbackMatchMetadataSource`.
- No se ha verificado si el orden de los jugadores en la lista `players` de `MatchTelemetry` siempre contiene exactamente 6 jugadores para el equipo 0 y 6 jugadores para el equipo 1 en partidas de abandono temprano o desconexiones.

---

## Resumen de Fuentes Consultadas

1. **Repositorio local**:
   - `src/main/java/sntiago05/rejuv/clients/dto/ItemAsset.java` (líneas 1-20)
   - `src/main/java/sntiago05/rejuv/clients/dto/AccountRank.java` (líneas 1-11)
   - `src/main/java/sntiago05/rejuv/clients/dto/MatchHistoryEntry.java` (líneas 1-24)
   - `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java` (líneas 1-56)
   - `src/main/java/sntiago05/rejuv/services/GameAssets.java` (líneas 1-104)
   - `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java` (líneas 1-28)
   - `src/main/java/sntiago05/rejuv/domain/PlayerTelemetry.java` (líneas 1-26)
   - `src/main/java/sntiago05/rejuv/domain/StatSnapshot.java` (líneas 1-13)
   - `src/main/java/sntiago05/rejuv/domain/ItemPurchase.java` (líneas 1-12)
   - `src/main/java/sntiago05/rejuv/domain/PowerUpBuff.java` (líneas 1-18)
   - `src/main/java/sntiago05/rejuv/clients/DeadlockApiRequests.java` (líneas 1-160)
   - `src/test/resources/fixtures/deadlock-api/assets-items.json`
2. **Especificación OpenAPI upstream**:
   - `https://api.deadlock-api.com/openapi.json` (OpenAPI v3.1.0, 130 rutas, schemas `SteamProfile`, `SteamFriend`, `Item`, `Upgrade`, `Ability`, `Weapon`).
3. **Llamadas HTTP reales secuenciales (3 ejecutadas)**:
   - `GET /v1/players/steam?account_ids=139205933` -> HTTP 200 OK.
   - `GET /v1/players/steam?account_ids=139205933,999999999` -> HTTP 200 OK.
   - `GET /v1/assets/items/1548066885` -> HTTP 200 OK.
