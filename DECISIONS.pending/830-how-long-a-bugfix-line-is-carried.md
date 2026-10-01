# A bugfix line ends when Camunda stops maintaining its minor, and not before

Decision 52 says why there are three lines and why 8.8 did not end on the day 8.10 went GA. What it
does not say is when a line does end, so today a line ends when somebody ends it on purpose. That is
not a promise. Whoever picks `2.0.0-8.8` cannot read anywhere how long a fix will still reach them,
and the matrix grows by a column with every minor because nothing ever takes one away.

This entry is the missing sentence. Everything below is measured or quoted. The rule itself is the
one open part, and three ways of writing it are at the end, with a default.

## What Camunda promises, and where that is written

Camunda's release policy says: "Camunda provides a standard support policy of 18 months for a
particular minor version from the date it is released." The same policy says a minor arrives in
April and October, and that at least the last three released minors get patch releases. Read on
2026-10-01 at `https://docs.camunda.io/docs/reference/announcements-release-notes/release-policy/`
and at `https://camunda.com/release-policy/`.

The dates per minor are a table of their own, in the release notes overview of the 8.10
documentation, read on the same day at
`https://docs.camunda.io/docs/next/reference/announcements-release-notes/overview/`:

| Minor |    Released     | End of standard maintenance |
|-------|-----------------|-----------------------------|
| 8.7   | 8 April 2025    | 13 October 2026             |
| 8.8   | 14 October 2025 | 13 April 2027               |
| 8.9   | 14 April 2026   | 12 October 2027             |
| 8.10  | 13 October 2026 | 11 April 2028               |

Every row of it is 18 months and the releases are six months apart, so Camunda keeps three minors in
maintenance at any moment, and the oldest of them drops on the day the next one arrives. 8.7 drops
on 13 October 2026, which is the day the table gives 8.10. Our lines are the three VanillaBP 2 ships
against, 8.8 and 8.9 and 8.10, and 8.7 never got one.

One thing about the table matters before anybody reads a date off it. It names the planned release
date of a minor, not the day the artifact appeared. Camunda published `8.10.0` on 2026-09-29, which
is the date our README gives for the GA, and the table says 13 October 2026. The end of maintenance
is a date in that table and not a date we compute from a release, so the two never have to be made
to agree.

## Why the lines exist

Decision 11: the client an artifact was compiled against is the lowest cluster version that artifact
accepts. Camunda promises a client against clusters of its own version and newer and says nothing
about the other direction, and the other direction really does fail. The blueprints ran a build
against `camunda/camunda:8.8.34` with the adapter compiled against the 8.9 client, and every job
activation came back with `Request property [tenantFilter] cannot be parsed`.

One artifact therefore cannot serve two minors, and decision 52 draws the conclusion from it: a
VanillaBP bugfix has to be deliverable without a Camunda cluster upgrade. Ending 8.8 on the day 8.10
went GA would have told everybody on an 8.8 cluster to upgrade before they can have the next fix,
while Camunda itself still fixes that cluster until April 2027.

The reason also hands over the shape of the answer. A line ends at the moment the cluster it serves
stops being a cluster anybody has to be able to get a fix for.

## What the lines cost

The matrix builds one line per `line-*` profile of the root POM, each against the cluster its own pin
names. Measured on the three nightly runs up to 2026-10-01:

|    Run     |  8.8  |  8.9  | 8.10  | Runner time | Waiting |
|------------|-------|-------|-------|-------------|---------|
| 2026-09-29 | 34:58 | 31:39 | 33:04 | 99:41       | 35:23   |
| 2026-09-30 | 34:21 | 31:46 | 33:00 | 99:07       | 34:47   |
| 2026-10-01 | 36:03 | 30:49 | 32:10 | 99:02       | 37:00   |

A line costs about 33 minutes of a runner. The lines run at the same time, so one run of the matrix
costs about 100 runner minutes and about 35 minutes of waiting, and a fourth line adds the runner
minutes without adding to the wait unless it is the slowest one.

How often that is paid is the other half of the bill. `checks.yaml` calls the matrix for every pull
request without a condition, and the night calls it once more. In the seven days up to 2026-10-01
there were 55 runs of `checks.yaml` and 7 nights, so 62 runs of the matrix, which is about 103 runner
hours in a week. A fourth line makes that about 137.

What a line costs in work is close to nothing, because every line is built from this one source tree.
The delta is `core/src/main/java-line-<id>`, the same five class names on every line, and it is 340
lines on 8.8 and on 8.9 and 382 lines on 8.10. A fix in the shared code is on every line the moment
it is merged.

So the bill of a line is the matrix, and nothing else about a line is expensive.

## The ways to say how long a line is carried

### Camunda's own date

A line ends on the day Camunda's end of standard maintenance for its minor arrives, and not earlier.

This says when a line ends and not which lines exist. Which lines exist stays decision 52 and
whatever the release decides. 8.7 is maintained until 13 October 2026 and has no line, and this rule
does not open one.

The user side of it is a date they can look up today, and the date is Camunda's: while Camunda still
fixes your cluster, VanillaBP still fixes the adapter for it. That is the promise the lines were
built for, and it is worded in a number nobody here invented.

Our side is one column per maintained minor. Camunda maintains three, and a preview line sits beside
them from the first pre-release of the next minor until its GA, so the matrix is three columns for a
short while after a GA and four for most of a cycle. 8.10 ran from its first alpha on 2026-05-11 to
its release on 2026-09-29, which is about four and a half months out of every six. That fourth
column is the 34 runner hours a week which follow from the numbers above.

The dates line up on top of that. 8.8 leaves maintenance on 13 April 2027 and the next minor is due
in the same April, so the column 8.11 adds is the column 8.8 gives back.

### A number of our own

A line ends when it is no longer one of the three newest GA lines.

Our side is capped by construction, at three GA columns plus the preview one, whatever Camunda does
with its cadence. The cap is all this way buys, and today it buys nothing, because counting the three
newest GA lines picks the same 8.8, 8.9 and 8.10 that Camunda's dates pick.

The user side is weaker. A date is something somebody can plan with, and a count is something they
have to work out by watching our releases. The two rules also part company as soon as Camunda changes
something. A faster cadence would have us drop a line Camunda still patches, which is what decision
52 refused to do, and a longer maintenance would have us keep a line Camunda has stopped patching.

### Leaving it open

A line ends when somebody ends it deliberately, which is the state today.

It costs nothing to write down and it is the only one of the three which answers nobody. Every minor
adds a column and no rule takes one away, so the matrix only grows, and the question comes back at
every boundary. The Renovate boundary rule already asks it in the body of its pull request, and
there is nothing there to answer it with.

## The default

Camunda's own date. It rests our promise on a number nobody here makes up, and that promise is the
one the lines were built for. It also costs nothing beyond what the matrix runs today, because 18
months of maintenance over a six month cadence is the three lines we already build.

## What follows once it is yes

A line stays in the POM and in the matrix until its date, and it gets its last release before that
date. After the date its `line-*` profile goes, which takes the line out of the matrix on its own,
and what was published stays in the registry.

Two texts say it already, written to the default in the same commit as this file: the `README.md`
section "How long a line lives" and the sentence of decision 52 which left this open. Pick another
way and both of them change with it. Two more wait for the word either way, namely the wiki section
about moving to another line and the question in the body of the Renovate boundary pull request,
which can then ask about a date instead of asking whether the oldest line is still carried.

## What is open

The rule, and only the rule.
