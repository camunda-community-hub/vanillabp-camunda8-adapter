# A new decision, from story 636: which lines the adapter runs now that 8.10 is GA

This is a new decision and needs a number. It supersedes decision 47, which says the 8.10 line is
the preview line and pins a release candidate. Decision 47 stays where it is and gets the note that
this entry replaced it, with this entry's number in it.

Several places rely on it: the three `line-*` profiles of `pom.xml` and the default properties
beside them, the line table and the section "How long a line lives" of `README.md`, the three
custom managers and the boundary rule of `renovate.json`, the line section of `UPGRADE.md` and the
page *Home* of the wiki.

Camunda released `8.10.0` on 2026-09-29, two weeks before the date it had announced.

## The entry

> ### Three lines after the GA of 8.10, two of them for bugfixes, and no preview line until 8.11
>
> The rule until 2026-09-29 was that a line ends when the next minor goes GA, so there were two GA
> lines at a time plus a preview line. Under that rule the GA of 8.10 would have ended the 8.8 line
> on the day it arrived.
>
> It did not end it. The lines exist for one reason, which is decision 11: a VanillaBP bugfix has to
> be deliverable without a Camunda cluster upgrade. Ending 8.8 on that day would have told everybody
> on an 8.8 cluster to upgrade before they can have the next fix, and Camunda supports 8.8 until
> April 2027. Their cluster would have been in support while their adapter was not.
>
> So the lines are 8.8, 8.9 and 8.10. 8.10 is the current GA line, which means a plain build
> produces it and a feature lands on it. A cluster of 8.10 or newer takes it. 8.8 and 8.9 take fixes
> and nothing which needs a newer cluster. There is no preview line, and the next one is the line
> built against the first pre-release of 8.11.
>
> The price is CI. Three lines are three cluster runs per pull request, and the 8.11 preview line
> will make it four. The work per line is close to nothing, because every line is built from one
> source tree, so what a line costs is the matrix and not the maintenance.
>
> `2.0.0-8.10` is published with the 2.0 release, like the other two. The line is GA, and a user who
> runs 8.10 should not have to point at a pre-release to get the adapter built for their cluster.
>
> What this entry does not say is how long a bugfix line is carried. Ending one needs that statement,
> and the statement is worth more than the saved build, so until it exists a line ends when somebody
> ends it deliberately. The Renovate boundary rule asks the question at the moment it matters: a
> minor bump of a pin waits for approval and its body asks whether the oldest line is still carried.
>
> See [Release lines](./README.md#release-lines).

## What also goes in with the number

Decision 47 gets its superseded note, the way decision 43 carries one.
