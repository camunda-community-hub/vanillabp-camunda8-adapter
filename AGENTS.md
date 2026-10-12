# Working on camunda8-adapter

This is the VanillaBP adapter for a remote Camunda 8 cluster, on Spring Boot and on Quarkus. One
source tree is released once for every Camunda minor version.

Read [`README.md`](./README.md) first. It says which release line you are building, what needs
secondary storage, how the adapter runs what it delivers, and how to build. The wiki is written for
users of the adapter; the README is written for its contributors.

[`ADAPTER-AUTHORS.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/ADAPTER-AUTHORS.md)
in the platform repository describes what this adapter implements. It does so once, for every
adapter: the two interfaces, the calls the core expects back, what an answer promises and what a
wrong one costs. Read it before you change anything on the SPI boundary. Where this adapter does
something different, the difference and its reason belong in the README.

## What belongs in `UPGRADE.md`

[`UPGRADE.md`](./UPGRADE.md) describes the step from VanillaBP 1 to the 2.0 release and nothing
else. A change gets an entry when a version-1 application on this BPMS behaves differently or has
to change something. A change between two snapshots of 2.0 gets no entry, however much work it
was. Such an entry would make the reader follow how the release was built instead of doing their
own upgrade, and git holds that history anyway.

What does not go there still has a place. The end state of a new feature belongs in the wiki,
because users read the wiki. A reason which several places in this repository rely on belongs in
[`DECISIONS.md`](./DECISIONS.md). Anything else belongs in the commit message.

The file is organised by version line and then by topic, and no heading carries a date. It holds
what this adapter asks of an upgrading application. What VanillaBP itself asks is in the
[platform's file](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md).
The wiki page
[Migrating from version 1](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1)
tells users what both files say. Where they disagree, the wiki page wins.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions which several places in this repository rely
on. It is the ONLY thing the code is allowed to cite, in the plain form
`see decision 7 in the repository's DECISIONS.md` which grep can find. The code cites only entries
of THIS repository.

Read it before you change behaviour. An entry is not background reading. It is the reason why the
code around it looks the way it does. So a change which contradicts an entry is wrong until the
entry says otherwise.

**A decision is changed or replaced only after asking.** If your change would make an entry
untrue, stop and ask the maintainer before you write the change. If the answer is yes, the same
commit updates the log. The old entry STAYS, marked as superseded, and names the entry which
replaced it. The new decision takes the next free number. Numbers are never reused and never
renumbered, because a citation in an older release still points at them. Never edit an entry until
its old text is gone.

The same rule holds for adding an entry. A decision earns a number when several places rely on it
and a copy of the explanation in each of them would go stale. Anything smaller is a comment where
it belongs. Anything larger is documentation.

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: the README files, `DECISIONS.md`, this file, the
Javadoc and comments which explain something, in the main code and in the tests, and the texts
of commits and pull requests. It holds for the
[wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki) as well,
because the wiki clone has no `AGENTS.md` of its own.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys, artifact coordinates and the headlines of decision log entries stay as
they are, because code, tests and other repositories point at them.

Before and after, from this repository:

> Classes compiled against one client are binary compatible with no other one, so a stale `target`
> directory does not fail to compile, it fails at runtime with a `NoClassDefFoundError`.

becomes

> Classes compiled against one client are not binary compatible with any other client. So a stale
> `target` directory still compiles, and then fails at runtime with a `NoClassDefFoundError`.

This one is from `CONTRIBUTING.md`. The next one is from the README:

> Whether a cluster accepts a credential is only learnable by asking it, so `method: none` cannot
> be validated at startup.

becomes

> You only learn whether a cluster accepts a credential by asking it, so `method: none` cannot be
> validated at startup.

And one from the wiki page about sizing:

> The heap is an indication and not an accounting: even a thousand workers are tens of megabytes,
> which no application sizes against.

becomes

> The heap figure is a rough guide, not an exact count: even a thousand workers take tens of
> megabytes, which no application sizes against.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your numbers against `origin/main` and
against every open pull request, before the pull request exists.

It went wrong twice on 2026-09-13, in the Business Cockpit repository and not in this one. Two
branches claimed one number, which had to become 19 and 20. Two more claimed the next one, which
had to become 21 and 22. Both times it showed up at the merge, which is the worst moment for it.
A merge happens on GitHub, and nobody can change a `see decision 21` in a Java file there.

The check:

```bash
bin/check-decision-numbers.sh
```

The script reports and changes nothing. By hand it is:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^### [0-9]+\. '  # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+### [0-9]+\. '                   # for each open pull request
```

The entries of this repository are `###` headings. `gh pr diff` takes no path argument, so the
grep does the filtering.

If your number is taken, your entry gets the next free one. Then correct every citation of it in
the code and in the documentation.

Read each citation before you change it. Not every `see decision <n>` in the branch is about your
decision. A branch can cite a number somebody else handed out long ago, and that citation stays
as it is. A search and replace over the branch turns a right reference into a wrong one.

None of this breaks the rule that a number is never renumbered. That rule is about a merged
number, which a citation in a released artifact points at. Until the pull request is merged,
nothing outside the branch has seen the number. So correcting it costs nothing outside the branch.

Every other running number is checked the same way. The story prompts are such a series. They are
kept outside this repository, so they are checked where they are kept.

After you renumber, run `bin/check-decision-citations.sh`. It says whether every citation of a
decision still points at an entry, also where a citation is wrapped over two lines. The *Checks*
workflow runs it on every pull request.

`bin/check-orphaned-javadoc.sh` finds a javadoc block which stands directly in front of a second
one. Javadoc drops such a block without a word, so its text appears nowhere. Run the script when
you wrote or moved a comment. If it reports a block, move the block back to the element it
describes; do not delete it.

## What code may point at

Code points at nothing which a later change can make wrong without anybody noticing. So no story
or prompt number, no issue or pull request number, no chat transcript, no person. Those record a
conversation at a point in time. A decision entry lives next to the code, and the same commit
which changes the code updates the entry. That is what makes it safe to cite.

Where a name can carry the reason, the better fix is the name. Where it cannot, a comment says why
in its own words and is complete where it stands. Only what several places have to carry becomes
an entry in the log.

Commit messages and pull request descriptions may cite whatever they like. They record a point in
time themselves.
