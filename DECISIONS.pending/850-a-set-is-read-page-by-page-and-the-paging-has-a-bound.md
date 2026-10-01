### A search whose answer is a set reads every page, and the paging stops at 10000 entries

A Camunda 8 search which says nothing about the page it wants is answered with 100 entries and
nothing in the answer says that there were more. Three searches of this adapter could have more
than 100 hits and read that one page: the versions a process has, the element instances of a
workflow history, and the children of a scope the walk for a task's own scope reads. None of them
failed at the ceiling. The startup check named the 100 oldest versions and missed the one just
deployed, the history showed the beginning of a workflow as the whole of it, and the aggregate of
a task in the 101st iteration of a multi-instance was written nowhere.

So a search whose answer is a SET reads every page, through `Camunda8SearchPages`, and a search
whose answer is ONE hit names its limit at the call site with a sentence saying why that number is
enough. Both halves are the decision: the second one is what keeps a search for a single workflow
from paging through a cluster.

The paging stops at 100 pages, which is 10000 entries. A reader which never stops is worse than
one which says where it stopped, and a set that large is a sign of a caller which should ask a
narrower question. Where the bound is reached, the caller writes a log line, because the records
these answers travel in have no field for "and there was more": the history record cannot say it,
and the version list cannot either. The version search therefore runs NEWEST first and is turned
back into oldest first afterwards, so the bound can only cut versions nobody asks about any more.

What this costs is one request per page while a workflow is being served. The scope walk reads
pages while an aggregate is pushed, and it stops on the page its element instance is on, so the
common case pays for one page. The walk itself still costs one search per element instance below
the scope, which is what it cost before.
