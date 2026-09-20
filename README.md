# Git Repo Historian

A conversational agent that answers questions about any local git repository, built with
[ADK for Kotlin](https://github.com/google/adk-kotlin) 1.1.0.

```
You > who has been touching business/kyc lately?
git_repo_historian > calls tool: whoTouched
git_repo_historian > Three people since June: ...

You > write release notes for the last 20 commits
git_repo_historian > calls tool: release_report
```

It exists mainly as a worked example. Rather than a hello-world, it exercises most of the
framework against real data — tools, session state, human-in-the-loop confirmation, both
multi-agent patterns, workflow agents, and callbacks — and the notes below record the things
that were not obvious from the documentation.

## Install

**Homebrew** (macOS and Linux):

```bash
brew install oyedsamu/tap/git-repo-historian
```

**Manual** — download the archive from
[Releases](https://github.com/oyedsamu/git-repo-historian/releases), unpack it, and put
`bin/git-repo-historian` on your `PATH`. Requires **Java 21+** (`brew install openjdk@21`).

**From source**:

```bash
./gradlew installDist    # build/install/git-repo-historian/bin/git-repo-historian
```

### Set your API key, once

The tool talks to Gemini, so you need your own key from
[AI Studio](https://aistudio.google.com/apikey) — it is free for this kind of use.

```bash
mkdir -p ~/.config/git-repo-historian
echo "GOOGLE_API_KEY=your-key-here" > ~/.config/git-repo-historian/.env
```

A `.env` in the current directory is read first, then `~/.config/git-repo-historian/.env`.
A real environment variable beats both, so `GOOGLE_API_KEY=... git-repo-historian` works too.

### Use it

```bash
cd ~/some/git/repo
git-repo-historian
```

It inspects the current directory by default. Point it elsewhere with `HISTORIAN_REPO`.

### Configuration

| Variable | Meaning |
|---|---|
| `GOOGLE_API_KEY` | Gemini key. Required. |
| `HISTORIAN_REPO` | Repository to inspect. Defaults to the working directory. |
| `HISTORIAN_WIRING` | `DELEGATE` or `TOOL` — how the changelog writer is attached. |
| `HISTORIAN_TRACE` | `1` to print an execution trace on exit. |
| `HISTORIAN_MAX_TOOLS` | Refuse tool calls beyond this many. |

### Development

```bash
./gradlew test      # 86 tests, no API key needed
./gradlew run       # run from source
./gradlew runWeb    # ADK dev UI on http://localhost:8080
```

The dev UI lives in its own `webui` source set, so Ktor and Netty stay out of the shipped
distribution.

## What it can do

**Repository questions** — `recentCommits`, `commitsBy`, `whoTouched`, `filesChangedIn`.
The model chains them on its own: "what did Ada change in her most recent commit?" becomes
`commitsBy` then `filesChangedIn`.

**Bookmarks** — `bookmarkCommit`, `listBookmarks`, `clearBookmarks`, kept in session state
under a `user:` key. Clearing requires confirmation.

**Release reports** — a fixed pipeline rather than LLM routing:

```
commit_gatherer ──▶ ParallelAgent( changelog_drafter ‖ risk_reviewer ) ──▶ report_assembler
```

Each step passes its output to the next through session state via `outputKey`.

## Notes from building it

Things that cost time, in case they save you some.

**Set `@Tool(description = ...)` explicitly.** Without it KSP emits the placeholder
`"Function <name>"`, leaving the model nothing but the function name to route on.

**Rich return types work.** The docs demonstrate `Map<String, String>`, but a data class
generates a nested `OBJECT` schema with per-field properties, `List<T>` generates `ARRAY`
with `items`, and nullable types get `nullable = true` and drop out of `required`.

**Default arguments must be nullable.** `limit: Int = 20` fails KSP outright. Use
`limit: Int? = null` and apply the default in the body — the generated `execute` rebuilds
arguments from a JSON map, so a non-nullable default has no representation.

**Tool arguments are untrusted input.** The model fills them. A path or author argument
beginning with `-` is read by git as an *option*, not data. See `requireNotAnOption`.

**Return expected failures, don't throw.** Generated tool code doesn't catch exceptions, so
`filesChangedIn` returns `CommitLookup(found, error, commit)` instead.

**A `@Tool` may declare a `ToolContext` first parameter.** KSP injects it and leaves it out
of the schema the model sees. `suspend` tool functions work too.

**Read the pending state delta before committed state.** Writes go to
`context.actions.stateDelta`, reads come from `context.context.state`. The model batches tool
calls within one turn, so an earlier write is still uncommitted — read committed state only
and you silently lose it.

**`{key}` instruction templating does not exist in the Kotlin SDK.** That is a Python-side
feature. The Kotlin equivalent is `Instruction { ctx -> ... }`, a lambda receiving the
context, where you read `ctx.state[key]` yourself. Build the result with
`Content.fromText(role, text)` — role is the first argument.

**`sub_agents` and `AgentTool` are not interchangeable.** A sub-agent is a handoff: the model
emits `transfer_to_agent`, control moves, and the child answers the user directly until it
transfers back. An `AgentTool` is a function call: the parent gets a string and keeps control,
at the cost of an extra LLM call. Wiring both at once gives the model two routes to the same
specialist.

**Callbacks are per-agent constructor parameters, not global.** A nested agent built without
them is invisible to your tracing, so they have to be threaded to every child.

**`ReadonlyContext.branch` is non-null only inside a `ParallelAgent`**, formatted
`<parallelAgent>.<child>`. It is the only reliable way to distinguish genuine parallelism
from parent/child span nesting — overlap alone proves nothing, since a parent's span always
contains its children's.

**`ParallelAgent` children run on different coroutines.** Anything a callback writes to must
be thread-safe.

**`git log` exits non-zero in a repository with no commits yet**, because there is no HEAD.
That is an empty history, not a failure, and it must not throw out of a tool call.

**macOS JVMs ignore `HOME` for `user.home`**, deriving it from the OS user record instead.
That is wrong under `sudo -u`, in containers and in CI, so config lookup reads `HOME` first.

## Tests

```bash
./gradlew test
```

86 tests, none of which call a model or need an API key. Three tiers:

- **Behaviour** against real throwaway git repositories built per test.
- **Pure logic** at the seams — prompt builders, bookmark rules, trace span pairing. Anything
  touching a `ToolContext` is effectively untestable, since one needs a 19-argument
  `InvocationContext`, so the decisions live in plain functions and the framework types stay
  at the edge.
- **Structure** — agent wiring, pipeline order, output keys. Agent graphs are inspectable
  without calling a model, which makes this the cheapest useful tier.

## License

MIT
