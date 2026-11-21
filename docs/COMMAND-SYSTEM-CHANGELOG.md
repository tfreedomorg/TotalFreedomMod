# Command System Rewrite — Changelog

**Branch:** `cmd-system-rewrite` (compared against `devel`)
**Scope:** 288 files changed, ~10,100 insertions, ~10,650 deletions.

This branch replaces the legacy reflection/regex command framework
(`me.totalfreedom.totalfreedommod.command`) with a new annotation-driven,
Brigadier-native framework (`me.totalfreedom.totalfreedommod.cmd`). Every
command in the plugin was ported to the new framework, the old package was
deleted, and a number of commands were merged or removed along the way. A few
supporting systems (plugin singleton access, login processing, SSH dispatch,
messaging) were reworked to match.

---

## Table of contents

1. [The old system (what was removed)](#1-the-old-system-what-was-removed)
2. [The new command system (`cmd` / `cmd.internal`)](#2-the-new-command-system)
3. [The resolver system (`cmd.resolver`)](#3-the-resolver-system)
4. [How to write a command in the new system](#4-how-to-write-a-command)
5. [Framework-level semantic changes](#5-framework-level-semantic-changes)
6. [Command consolidations, removals, and additions](#6-command-consolidations-removals-and-additions)
7. [Notable per-command behavioral changes](#7-notable-per-command-behavioral-changes)
8. [Supporting changes outside the command system](#8-supporting-changes-outside-the-command-system)

---

## 1. The old system (what was removed)

The entire `me.totalfreedom.totalfreedommod.command` package is gone. It
consisted of:

- **`FreedomCommand`** (550 lines) — abstract base class. Commands declared
  metadata with `@CommandParameters(description, usage, aliases)` (aliases as a
  comma-separated string) and `@CommandPermissions(level, source, permission)`.
  Argument routing was done with `@CommandDispatchTarget(pattern, switches,
  priority)`: the pattern string (e.g. `"<player:Player> <value:Boolean>"`) was
  compiled into a **regular expression** (`<x>` → `\S+`, `<x..>` → `.+`), and
  at execution time the joined argument string was matched against each
  compiled pattern in `DispatchTargetPriority` order (HIGH/MEDIUM/LOW). The
  first match won; resolver names embedded in the token
  (`<mode:Enum:class=...,mode=UPPERCASE>`) drove string→object conversion. If
  nothing matched, execution fell through to the abstract
  `run(sender, playerSender, cmd, label, args, senderIsConsole)` method.
  The base class also held **per-invocation mutable state** (`sender`,
  `playerSender`, etc. set via `setVariables()` before dispatch).
- **`CommandHandler`** (401 lines) — JAR/directory scanner that discovered
  `Command_*` classes, instantiated them, wrapped each in an executor, and
  registered them with Paper as *Bukkit-style* basic commands
  (`registrar.register(name, description, aliases, basicCommand)`). It also
  owned the by-name argument-resolver map and the static command registry.
- **`FreedomCommandExecutor`** (264 lines) — per-command `CommandExecutor`
  that ran the permission check (rank level / TFM permission node / source
  type / SSH-Discord-telnet gates) before delegating to
  `FreedomCommand.runCommand`, echoed the usage string when a handler
  returned `false`, and gated tab completion.
- **`FreedomBasicCommand`, `AbstractCommandBase`, `CommandContext`,
  `CommandParameters`, `CommandPermissions`, `CommandDispatchTarget`,
  `DispatchTargetPriority`, `CommandFailException`, `SourceType`,
  `CommandLoader`** — supporting types, all deleted (a few were re-created in
  the new package, see below).
- **109 `Command_*` classes** — all deleted; 96 were ported to `cmd/`,
  13 were merged into other commands or removed (section 6).

To the server, every legacy command was a single opaque node taking one greedy
string; all parsing, validation, and tab completion happened inside the plugin
at execution time.

---

## 2. The new command system

New packages:

```
me.totalfreedom.totalfreedommod.cmd            — FCommand base, commands, loader, registry, messaging
me.totalfreedom.totalfreedommod.cmd.internal   — the engine (processor, gates, registries)
me.totalfreedom.totalfreedommod.cmd.internal.annotation — the declaration DSL
me.totalfreedom.totalfreedommod.cmd.resolver   — argument resolvers
```

### Core pieces

| Class | Role |
|---|---|
| `FCommand` | Stateless base class for command declarations. Provides helpers (`msg`, `adminAction`, `getPlayer`, `kickPlayer`, scheduler shortcuts, etc.), all of which now take the sender explicitly. |
| `CommandLoader` (service) | Registers all argument resolvers into `ResolverRegistry`, then walks the plugin JAR for `Command_*` classes in the `cmd` package and instantiates each `FCommand`. On service stop it clears the command registry, processor map, cooldowns, and resolver registry so a plugin reload starts clean. |
| `CommandHolder` | Thin facade: `register(FCommand)` stores the instance in `CommandRegistry` and queues it with `CommandProcessor`. |
| `CommandRegistry` | Name → `FCommand` instance map (aliases included; primary names win collisions via `putIfAbsent`). Used by `/help` (httpd `Module_help`) and anything that needs command metadata at runtime. |
| `CommandProcessor` | The heart of the system. Reflects over one `FCommand`, builds a full **Brigadier node tree** from its annotations, and registers it through Paper's `LifecycleEvents.COMMANDS`. The first `register()` call hooks the lifecycle event once; each time the event fires (startup **and** reloads) every stored processor re-registers, which fixes the reload-registration bugs the old system had. |
| `PermissionGate` | Centralized enforcement of `@Permission` (rank level, TFM permission node, source type, SSH/Discord/telnet channel gates, console custom-rank binding). Also wraps remote-session console senders in `AttributedConsoleSender`. |
| `CooldownManager` / `CooldownUnit` | In-memory per-player, per-(command:subcommand) cooldown expiry map (TICKS or SECONDS). |
| `FuzzyMatch` | Subsequence fuzzy matcher used for tab-completion filtering, scored by match position + gap penalty. |
| `MessageUtils` | MiniMessage-based messaging layer (parse/send/broadcast/console, placeholders, plain-text stripping) — replaces the legacy `FUtil.colorize`/section-sign pipeline for commands. Unknown `<tags>` in input are auto-escaped so player-supplied text can't inject formatting and literal text like `/freeze <player>` doesn't break parsing. |

### How a command becomes a Brigadier tree

`CommandProcessor.buildNode()` does the following per command:

1. **Collects `@Completer` methods** into a `(subcommand path, argument position)`
   lookup table (invalid signatures are warned about and skipped).
2. **Collects `@Callback` handler methods.** A handler with no `@Subcommand`
   (or an empty path) is a *root handler*; one with `@Subcommand("set default")`
   hangs off that literal path. Paths are space-separated and merged into a
   **trie**, so `"set"` and `"set default"` share one `set` branch, and a node
   can carry both handlers and children.
3. **Overload resolution by arity.** Multiple handlers may share the same path
   as long as they take different numbers of positional arguments (that's how
   optional arguments are modeled — e.g. `/expel`, `/expel <radius>`,
   `/expel <radius> <strength>` are three methods). Same-path, same-arity
   handlers are ambiguous and logged as warnings (the old system resolved such
   conflicts with the now-removed `DispatchTargetPriority`).
4. **Wires the class-level `@Permission` into the root node's `requires()`**,
   so unauthorized senders don't just get denied — they don't *see* the
   command in tab completion at all (a genuinely new behavior; the old system
   registered everything and denied at execution time).
5. **Builds the positional argument chain** for each handler, innermost-first
   (a Brigadier constraint — the leaf carries `executes()`, each parent wraps
   the next via `.then()`). Parameter types map as follows:
   - `String`, `int`, `long`, `double`, `float`, `boolean` → native Brigadier
     argument types (client-side validation and red-highlighting for free);
   - `Player` (non-sender position) → Paper's player selector argument, so
     **selectors like `@p` now work** where a Player parameter is declared;
   - enums → captured as a word, resolved at dispatch time (uppercased) via
     `EnumArgumentResolver`, with automatic fuzzy suggestions of constant names;
   - any type registered in `ResolverRegistry`, or any parameter annotated
     `@Resolve` → captured as a word and handed to the resolver at dispatch;
   - a trailing `String` annotated `@Greedy` → consumes the rest of the line
     (replaces the legacy `<arg..>` ellipsis).
6. **Generates switch branches.** Each `@Switch("s") boolean` parameter becomes
   a literal `-s` branch rather than a positional argument; the processor emits
   one path per *subset* of a handler's switches in declaration order, each
   leading into the same positional chain. Presence/absence arrives in the
   handler as `true`/`false`.
7. **Attaches tab completion** per argument position: an explicit `@Completer`
   wins; otherwise resolver-supplied candidate lists (e.g. Material registry
   keys), then enum-constant fuzzy matching, then Brigadier/Paper defaults.
8. **Usage fallback:** if no zero-argument root handler exists and `usage` is
   set, bare invocation prints `Usage: ...` (with `<command>` replaced by the
   label actually typed, namespace stripped).

### What happens at dispatch time

The generated `Command<CommandSourceStack>` for each handler:

1. Resolves the effective sender (wrapping SSH/Discord session consoles in
   `AttributedConsoleSender`).
2. Runs `PermissionGate.test` with the **method-level `@Permission` if present,
   else the class-level one** — so a single command can have differently-gated
   subcommands.
3. Enforces the handler's declared sender type (a `Player` first parameter on
   a console invocation → "This command can only be used by players.").
4. Checks/starts the `@Cooldown` for player senders (keyed
   `command:subcommandPath`, `{remaining}` placeholder in the message).
5. Extracts and resolves each argument (custom resolvers, player selector,
   enum, or native type).
6. Invokes the method. `void` returns count as success; a `Boolean` return is
   honored for Bukkit-style semantics. Exceptions are handled centrally:
   - `CommandFailException` → its message is MiniMessage-parsed and sent to the
     sender (the standard "abort with a user-facing error" mechanism);
   - `ArgumentResolutionException` → its pre-formatted component is sent;
   - anything else → logged with root cause, generic red "Command error: ..."
     to the sender.

---

## 3. The resolver system

The resolver *interfaces* survive from the old system
(`AbstractArgumentResolver<T>` with `name()` and `resolve(String arg, String
strategy)`, plus `AbstractParameterizedArgumentResolver<T>` which parses the
strategy string into a `k=v` map) — but everything around them changed:

### Registration: name-only → name + type + suggestions

The old `CommandHandler` registered resolvers **by name only**, and commands
had to reference them textually inside pattern tokens
(`<mode:Enum:class=me...CommandSpyMode,mode=UPPERCASE>`). The new
`ResolverRegistry` supports three registration shapes:

```java
ResolverRegistry.register(resolver);                        // by name only → opt-in via @Resolve("Name")
ResolverRegistry.register(resolver, Material.class);        // + bound to a parameter type → automatic
ResolverRegistry.register(resolver, Material.class, keys);  // + default tab-completion candidate supplier
```

Type-bound resolvers make handler parameters of that type (e.g. `Material`,
`Enchantment`, `EntityType`, `PotionEffectType`, `OfflinePlayer`, `Plugin`,
`Date`, `InetAddress`, `Key`, `WorldTime`, `WorldWeather`, `ProtectedRegion`)
resolve **automatically with no annotation at all**. `@Resolve` is only needed
to (a) pass a strategy hint — `@Resolve(strategy = "blocks")` on a `Material`,
`@Resolve("DateOffset")` on a `Date` — or (b) name a resolver explicitly when
the parameter type alone is ambiguous (e.g. `@Resolve("MaterialQuery")` on a
`List<Material>`).

Suggestion suppliers double as the default tab-completion source for that
type. Registry-derived lists (materials, enchantments, potion effects, entity
types) are **memoized** on first use; plugin names and protected-region names
are computed live because they change at runtime.

### Resolution flow

Custom-resolved parameters are declared to Brigadier as a single *word*
argument; at dispatch time the raw token is passed to
`resolver.resolve(raw, strategy)`. A thrown `ArgumentResolutionException`
aborts the handler and messages the sender with the resolver's formatted
error. This is the same division of labor as the old system (Brigadier/regex
captures text, resolver converts it), but resolution moved from the regex
fold in `FreedomCommand.dispatchCommand` into the processor's dispatch
lambda.

### Resolver roster changes

- **Moved:** all resolvers relocated from `command.resolver` to `cmd.resolver`
  (contents unchanged for the scalar/registry ones).
- **New — `WorldTimeArgumentResolver`:** maps aliases via
  `WorldTime.getByAlias`, defaulting to `NOON` for unknown input.
- **New — `WeatherArgumentResolver`:** maps aliases via
  `WorldWeather.getByAlias`, defaulting to `OFF` for unknown input.
- **Renamed/reworked — `ProtectedRegionArgumentResolver`** (was
  `ProtectedRegionResolver`): now constructor-injected with the plugin
  instance instead of reaching for a static singleton, and registered with a
  live suggestion supplier (`plugin.pa.getProtectedAreaNames()`, a new
  `ProtectArea` API added on this branch). Unknown region names throw a
  descriptive `ArgumentResolutionException`.
- The enum resolver is now also the engine behind *implicit* enum parameters:
  the processor forces `mode=uppercase` so raw tokens are uppercased before
  `fromString`/`valueOf` lookup.

---

## 4. How to write a command

```java
@Command(name = "example", description = "Does things.",
         usage = "/example [-s] <player> [amount]", aliases = {"ex"})
@Permission(permission = "tfm.example", level = Rank.SUPER_ADMIN, source = SourceType.BOTH)
public class Command_example extends FCommand
{
    // Bare "/example <player>" — root handler, no @Subcommand.
    @Callback
    public void run(CommandSender sender, Player target, @Switch("s") boolean silent)
    {
        // ...
    }

    // "/example <player> <amount>" — same path, different arity = optional arg.
    @Callback
    public void run(CommandSender sender, Player target, int amount, @Switch("s") boolean silent)
    {
        // ...
    }

    // "/example reset confirm" — nested literal path, its own permission gate.
    @Callback
    @Subcommand("reset confirm")
    @Permission(permission = "tfm.example.reset", level = Rank.SENIOR_ADMIN)
    @Cooldown(value = 30, unit = CooldownUnit.SECONDS)
    public void reset(CommandSender sender, @Resolve("DateOffset") Date since, @Greedy String reason)
    {
        if (somethingWrong)
        {
            throw new CommandFailException("<red>That didn't work.");
        }
    }

    // Custom tab completion for argument 0 of the root handler ("" = root path).
    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return FuzzyMatch.filter(candidates, partial);
    }
}
```

Rules of thumb:

- The class must extend `FCommand`, be named `Command_<name>`, live in the
  `cmd` package (the loader discovers it by filename), and carry `@Command`.
- The optional first parameter of a handler is the sender; declaring it as
  `Player` makes the handler in-game only. Everything after it maps
  positionally to arguments.
- Optionality = overloads with different arity, not nullable parameters.
- `@Switch` booleans are excluded from positional counting (including for
  `@Completer` positions) and must be typed as a contiguous prefix before the
  positional arguments (`/tempban -s -rb name 5m reason`).
- Messages use MiniMessage via the inherited `msg(sender, template,
  resolvers...)`; **always** pass player-controlled text through
  `Placeholder.unparsed`/`MessageUtils.unparsed` so it cannot inject tags.
- Throw `CommandFailException` to abort with a user-facing (MiniMessage)
  error from any depth.

---

## 5. Framework-level semantic changes

These apply to *every* command, and are the intentional behavioral deltas of
the rewrite:

1. **Real Brigadier trees instead of one greedy-string node.** Clients now see
   per-argument structure: live validation, red highlighting of bad input,
   argument-aware tab completion, and selector support for `Player`
   parameters. Under the old system the client saw nothing until the server
   regex-matched the full string at execution time.
2. **Unmatched input no longer falls through to `run()`.** The abstract
   catch-all `run(...)` method is gone. Input that doesn't parse produces a
   Brigadier syntax error (or the usage fallback for a bare invocation).
   Commands can no longer silently accept malformed argument lists.
3. **Unauthorized users no longer see gated commands.** The class-level
   permission is wired into `requires()`, hiding the command from completion
   and dispatch entirely. Previously the command was visible and denied on
   use.
4. **Per-subcommand permissions and cooldowns now exist.** The old system
   could only gate the whole command; `@Permission`/`@Cooldown` on a handler
   method gates just that path.
5. **Switch placement is stricter.** Legacy switches were stripped from the
   argument list wherever they appeared (`/tban player -s reason` worked).
   Switches are now literal Brigadier branches and must appear in declaration
   order *before* the positional arguments. This is a consequence of
   Brigadier's prefix-tree model — and in exchange, switches are visible and
   completable in the client.
6. **Priority-based dispatch is gone.** `DispatchTargetPriority` had no
   equivalent; handler selection is structural (path + arity), and genuine
   ambiguities are logged at registration instead of silently resolved by
   priority ordering.
7. **Handlers are stateless.** `FreedomCommand`'s per-invocation `sender` /
   `playerSender` fields (mutable instance state shared across invocations)
   are gone; the sender arrives as a parameter. This removes a whole class of
   cross-invocation bleed bugs and makes handlers safe to call concurrently.
8. **Messaging moved to MiniMessage.** Legacy `FUtil.colorize` +
   `NamedTextColor` parameters are replaced by MiniMessage templates with
   placeholder resolvers. Consequences: consistent hex/click/hover support,
   auto-escaping of non-tag angle brackets, and injection safety for player
   input via unparsed placeholders. The old `msg()` default of gray text is
   gone — color is now explicit in each template. Console messages are sent
   as components (Adventure handles console rendering) instead of being
   pre-serialized to ANSI inside the command layer.
9. **`getPlayer(name)` now fails fast.** The old helper returned `null` and
   every caller had to remember to null-check; the new one throws
   `CommandFailException("That player cannot be found!")`. (Exact match,
   then case-insensitive, then unique-prefix match — that logic is
   unchanged.)
10. **Return values.** `void` handlers are treated as success; returning
    `boolean` still works for Bukkit-style "false = show usage"-ish flows
    (false maps to Brigadier result 0, but no usage echo — usage echo only
    happens for bare invocations with no zero-arg handler).
11. **Registration is reload-safe.** Processors re-register on every
    `LifecycleEvents.COMMANDS` fire, and `CommandLoader.onStop()` clears the
    processor/registry/cooldown/resolver state. Command names and aliases are
    forcibly lowercased (Brigadier literals are case-sensitive).
12. **Permission-check parity.** `PermissionGate` is a near-verbatim port of
    `FreedomCommandExecutor.hasPermission`: identified SSH/Discord sessions
    require `tfm.manage.ssh`/`tfm.manage.discord`; non-player senders on the
    admin list require `tfm.manage.telnet`; `SourceType` gates; explicit TFM
    permission node takes priority over rank level; console senders can be
    bound to custom ranks via `ConsoleSenderRegistry`. No intentional change
    here — only *where* the check runs (Brigadier `requires()` + dispatch)
    changed.

---

## 6. Command consolidations, removals, and additions

Old package: 109 command classes → new package: 98.

### Merged into `/settings` (new command, aliases `/toggle`, `/set`, `/tfset`)

`Command_toggle` and all of its standalone sibling commands were consolidated
into one `Command_settings` with `query` (bare), `toggle`, and `set`
subcommands over a `Setting` enum:

| Removed command | Now |
|---|---|
| `/toggle` | `/settings toggle <setting>` |
| `/explosives` | `/settings [toggle\|set] explosives` (radius form: `/settings set explosives <radius>`, capped at 25) |
| `/fireplace` | `/settings … fireplace` |
| `/firespread` | `/settings … firespread` (also drives the `FIRE_SPREAD_RADIUS_AROUND_PLAYER` game rule) |
| `/fluidspread` | `/settings … fluidspread` |
| `/lavadmg` | `/settings … lavadmg` |
| `/lavaplace` | `/settings … lavaplace` |
| `/waterplace` | `/settings … waterplace` |
| `/petprotect` | `/settings … petprotect` |
| `/adminmode` | `/settings … adminmode` (still console-only, enforced per-setting) |
| *(lockdown, was a `/toggle` entry)* | `/settings … lockdown` (console-only) |

Additional settings exposed: `signplace`, `fallingblocks`, `fallingsigns`,
`entitywipe`, `nonuke` (with `/settings set nonuke <range> [count]`),
`autoclear`, `autotp`. The bare form (`/settings <setting>`) is a **query**
that reports current state — the old `/toggle` had no read-only form.

### Other merges/removals

- **`/tban` → `/tempban`** (alias `tban`, `noob`): one command with an
  optional `DateOffset` duration (default 5 minutes) and optional greedy
  reason (see section 7 for behavior deltas).
- **`/protectregion` + `/protectarea` → `/protectarea`**: merged into one
  class, backed by the new `ProtectedRegion` resolver with live name
  completion.
- **`/prelog` removed entirely.** Command pre-process logging
  (`ENABLE_PREPROCESS_LOG`) is no longer command accessible; this function is deprecated and slated for removal since paper already supplies this naturally.
- **`/adminmode` removed as a standalone** (absorbed by `/settings`, above).

### New infrastructure-level command surface

No brand-new player-facing commands were added beyond the consolidations;
`Command_settings` is the only genuinely new class.

---

## 7. Notable per-command behavioral changes

Beyond the mechanical port (annotations, MiniMessage, stateless handlers),
these commands *behave* differently:

- **`/settings explosives`** — toggle logic was fixed during the rewrite
  (toggling on restores the default 4.0 radius; toggling off zeroes it;
  explicit `set explosives <radius>` enables with a cap of 25). The old
  standalone `/explosives` set the radius without the enable/disable
  coupling.
- **`/lockdown` state now persists.** Lockdown used to be a runtime-only
  boolean on `LoginProcess` (`lockdownEnabled`), lost on restart. It is now
  `ConfigEntry.LOCKDOWN_MODE` backed by a new `lockdown_mode` key in
  `config.yml`, so a lockdown survives a server restart until explicitly
  lifted.
- **`/tempban`** — absorbs `/tban`. New: optional duration argument
  (`/tempban <player> 2h30m reason`) instead of the fixed 5 minutes;
  duration defaults to 5m when omitted. Changed: the lightning smite now
  only fires for non-silent bans (old `/tban` always smote the target), and
  the kick now applies to *every* online player matching the ban's IPs, not
  just the named player. Strike increment and rollback (`-rb`) behavior
  carried over.
- **`/radar`** — maximum/default radius raised from 64 to 200 (clamped
  1–200, default 200).
- **`/expel`** — logic unchanged (clamps 1–100 for radius and strength),
  but the fixed pattern set was replaced with true optional arguments;
  output is now hoverable player display names.
- **`/freeze`** — global on/off are now literal subcommands (`/freeze on`,
  `/freeze off`) rather than a parsed Boolean, so the old
  true/false/yes/no-style aliases only apply to the per-player form
  (`/freeze <player> [on|off]`, still Boolean-resolved). `purge` carried
  over.
- **`/gcmd`** — now dispatches through the server command map (so the
  target player runs the command through the full Brigadier pipeline), with
  the same blocked-command and don't-target-admins checks.
- **`/cmdspy`** — the devel-side improvements (distinct per-mode feedback
  messages including a proper "CommandSpy disabled." on turn-off, and
  tab completion of modes) were re-applied on this branch after having been
  lost in the initial port. Gains the `cspy` alias.
- **`/rankconfig`** — heavily slimmed (652 → 310 lines). The interactive,
  conversational property-editing wizard was removed; property changes are
  now explicit (`/rankconfig set <rank> <property> <value>`), with
  `list/create/edit/delete/setrank/reload/save` as flat subcommands and an
  overview menu on bare invocation.
- **`/baninfo`** — rewritten around partial-IP fuzzy matching (leading-octet
  matching against active bans) in addition to exact username lookup; see
  section 9 for a caveat.
- **`/list`** — filter flags (`-a` admins, `-i` impostors, `-f` famous) are
  now real switches on one handler instead of string-matched arguments.
- **Player-targeting commands generally** — anywhere a handler declares a
  `Player` parameter, Paper's player-selector argument is used: selectors
  (`@p`, exact names) resolve client-side-validated, and an empty resolution
  produces a uniform gray "Player not found." message.

Commands not listed here were ported with intent to preserve behavior;
message formatting (colors, hover/click affordances) differs broadly because
of the MiniMessage migration.

---

## 8. Supporting changes outside the command system

- **`PluginProvider` (new)** — replaces the `TotalFreedomMod.plugin()` static
  singleton with an explicit bind/unbind holder (`AtomicReference`,
  `bind()` in `onLoad`, `unbind()` in `onDisable`, `get()` throws if
  unbound). All former `TotalFreedomMod.plugin()` call sites
  (`ConfigEntry`, `SavedFlags`, `FSync`, `FUtil`, `PlayerData`,
  `PlayerListUtil`, `CommandBlockerEntry`, `CommandBlockerRank`,
  `ModuleExecutable`) now use `PluginProvider.get()`. `FCommand.plugin()`
  is backed by it, which is what lets command declarations be
  zero-arg-constructible.
- **`TotalFreedomMod`** — the `cl` service field was replaced by `cmdl`
  (the new `cmd.CommandLoader`); the static instance field and `plugin()`
  accessor were deleted.
- **SSH console (`SshConsoleCommandFactory` / `SshConsoleShellFactory`)** —
  previously bypassed the server dispatcher by locating a
  `FreedomCommandExecutor` directly (with a raw `Bukkit.dispatchCommand`
  fallback). Both paths now simply call
  `RemoteDispatchContext.dispatch(session, command)`, so SSH commands flow
  through the same Brigadier trees, permission gates, and attributed-sender
  wrapping as everything else.
- **`LoginProcess`** — substantially rewritten:
  - The login-gate checks (username validity, Force-IP, server-full with
    admin displacement, admin-only mode, lockdown) moved from the
    synchronous `PlayerLoginEvent` to `AsyncPlayerPreLoginEvent`, and kick
    messages are now components via `MessageUtils`.
  - `TELEPORT_ON_JOIN` / `CLEAR_ON_JOIN` are now keyed by **UUID** instead
    of case-insensitive username (commands like `/autotp`, `/autoclear`
    updated to match).
  - Auto-OP and the join-side effects (random teleport, inventory clear,
    admin-only/lockdown notices) moved into `PlayerJoinEvent` and run
    immediately instead of on a 20-tick delayed task; the "Your inventory
    has been cleared automatically." message was dropped.
  - Lockdown reads `ConfigEntry.LOCKDOWN_MODE` (see section 7).
- **`Fuckoff`** — the push-away vector math was fixed: it now computes the
  direction *away from* the admin (`target − admin`), guards against a
  zero-length vector (previously a possible NaN velocity/`normalize` on
  zero), pushes from the admin's position, and preserves the pushed
  player's yaw/pitch.
- **`FUtil`** — new `adminAction(CommandSender, Component)` and
  MiniMessage-resolver `bcastMsg` overloads; the legacy string/color
  `bcastMsg`/`adminAction` variants are `@Deprecated`.
- **`ProtectArea`** — new `getProtectedAreaNames()` (sorted,
  case-insensitive) feeding resolver tab completion.
- **`Module_help` (httpd)** — reads command metadata from `CommandRegistry`
  and the new `@Command`/`@Permission` annotations instead of the deleted
  `FreedomCommand` registry; rendering unchanged.
- **`config.yml`** — new `lockdown_mode: false` entry.

---

*Generated on branch `cmd-system-rewrite` at commit `dd0a24fb` ("Final
passthrough and removal of old command system")*
