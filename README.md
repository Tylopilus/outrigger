# Outrigger

A language server that sits in front of [jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls)
and adds analysis it lacks, without changing jdtls or the editor.

```
editor ──LSP──▶ outrigger ──LSP──▶ jdtls
       ◀──────             ◀──────
```

Every message is forwarded byte-for-byte, so jdtls extensions (nvim-jdtls
commands, debugging, test runners) keep working. Outrigger only rewrites what it
has something to add to. Right now that is the diagnostics jdtls publishes.

## Features

### Null analysis

Eclipse's null analysis only treats a variable as checked after a literal
`x == null` test, and assumes unannotated methods never return null. Outrigger:

- **Understands library null checks.** It drops jdtls' "may be null" warnings
  when a check provably rules null out:
  - `ObjectUtils.anyNull` / `allNotNull`
  - `Objects.isNull` / `nonNull` / `requireNonNull`
  - `StringUtils.isBlank` / `isEmpty` / `isNotBlank` / `isNotEmpty`

  It follows `!`, `||`, `&&`, if/else, ternaries, `while` and early
  `return`/`throw`, and gives up at any re-assignment of the variable.
- **Infers nullable methods.** A method whose `return` can yield `null`
  (directly, via a ternary, via another such method, or via a local variable
  holding such a value that isn't checked before the `return`) is treated as
  `@Nullable` without annotating it:
  - it reports uses of the result without a null check (source `outrigger`),
  - it drops jdtls' "Dead code" on null checks of the result,
  - it drops jdtls' "Null type mismatch" on the method's own `return`s.

  Calls are resolved with JavaParser's symbol solver: methods of nested and
  inner classes, calls qualified with a variable or a class name, and methods
  in the module's other files (same source folder) are covered. When exact
  resolution fails, e.g. because an argument's type comes from a library jar,
  the method is looked up by name and argument count in the type the call is
  made on. Overloads with the same argument count are left alone.
- **Tracks fields and captured variables.** Fields initialized with a nullable
  value and never assigned again are checked like local variables. A null
  check in a method also covers the variable's uses in lambdas and anonymous
  or local classes declared after it. Repeated expressions count as checked
  too: `map.get(k) != null && map.get(k).isEmpty()`.
- **Knows library methods.** Outrigger asks jdtls for each project's classpath
  (`java.project.getClasspaths`) and decides for library methods without a
  `@Nullable` annotation whether they can return null. They can if any of these
  says so:
  1. an override in `~/.config/outrigger/nullness.txt`,
  2. their annotations (`@Nullable`: jdtls reports those uses itself),
  3. their contract: the Javadoc from the `-sources.jar` next to the jar, or
     the JDK's `lib/src.zip` ("the page or `null`"),
  4. their code: a data-flow analysis of the bytecode. For interfaces, the
     code of their implementations in the directories configured as
     `implementations`, e.g. a local AEM's bundles, where `PageManager` and
     `Asset` are implemented.

  To keep this precise: for the JDK, `javax` and `jakarta` only the contract
  counts; implementations only count from the interface's own vendor
  (`com/day/cq/...` for `com.day.cq` interfaces) and never anonymous classes;
  an interface's default method counts by its implementations; `null` returned
  only for `null` input (`if (s == null) return null;`, "`null` if null input")
  doesn't count. In test sources, library-based warnings are off unless
  `libraryWarningsInTests=true`.

Limits:
- Check methods are recognised by name, whatever class they come from.
- Only local variables, fields that are never re-assigned and repeated
  expressions are tracked.
- A library method whose documentation says nothing and whose implementation
  isn't available counts as not nullable.

## Configuration

`~/.config/outrigger/config.properties`:

```properties
# Directories (separated by ":") searched for jars implementing library
# interfaces, e.g. the bundles of a local AEM. Indexed once, cached in
# ~/.cache/outrigger.
implementations=~/dev/aem/crx-quickstart/launchpad/felix
# Also report unchecked uses of nullable library methods in test sources
libraryWarningsInTests=false
```

`~/.config/outrigger/nullness.txt` overrides single methods:

```
com.day.cq.wcm.api.PageManager#getPage nullable
javax.servlet.ServletRequest#getRequestDispatcher nonnull
```

## Build

Needs Java 21 and Maven.

```sh
mvn package        # runs the tests, builds target/outrigger.jar
```

## Use

Put Outrigger in front of the command that starts jdtls:

```sh
outrigger -- java -jar .../org.eclipse.equinox.launcher_*.jar -configuration ... -data ...
```

`bin/outrigger` runs the jar with `$OUTRIGGER_JAVA`, or `java` from `PATH`.
Logs go to stderr, which editors usually collect in their LSP log. stdout is the
LSP channel.

### Neovim with nvim-jdtls

Prefix the jdtls command in the config passed to `start_or_attach`:

```lua
local jar = vim.fn.expand("~/dev/projects/outrigger/target/outrigger.jar")
if vim.fn.filereadable(jar) == 1 then
	config.cmd = vim.list_extend({ "java", "-jar", jar, "--" }, config.cmd)
end
require("jdtls").start_or_attach(config)
```

## How it works

| Package | Role |
|---|---|
| `rpc` | LSP base protocol: `Content-Length` framing, raw message bodies |
| `proxy` | Two pumps (client→server, server→client), `Feature` hooks |
| `document` | Mirrors open documents from `didOpen`/`didChange`/`didClose`, including the negotiated position encoding (UTF-8/16/32) |
| `nullness` | The null analysis, on a [JavaParser](https://javaparser.org) AST, with symbol resolution |
| `library` | Library methods: classpath jars, sources, bytecode analysis ([ASM](https://asm.ow2.io)), implementation index |

A `Feature` decides which diagnostics it wants to see and rewrites them.
Diagnostics for files that are not open are only analysed when they contain
something the feature cares about, since the file has to be read from disk.
Diagnostics published for an older document version than the one Outrigger
holds are passed through unchanged, and so is anything a feature fails on.

## Ideas

- Code actions, e.g. "add `@Nullable` to this method".
- Hover text explaining why a value may be null.
