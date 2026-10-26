# Microsoft Graph through the official SDK, despite its size

The Graph Java SDK is about 65 MB and makes the shaded jar about 100 MB, for four endpoints: list
folders, delta of a folder's messages, a message's MIME, and move. Plain HTTP calls with a JSON
library would be a few hundred kilobytes.

The SDK stays because the hard parts are in it and maintained by Microsoft: delta paging with
`@odata.nextLink` and `@odata.deltaLink`, the retry handler that honours `Retry-After` and backs off
on 429, 503 and 504, error parsing, and keeping up with Graph changes. Writing those by hand is the
kind of code this project wants to avoid owning. The size costs disk and image pull time once per
release, which matters less than a throttling bug in hand-written retry code.

Revisit if Microsoft ships a modular SDK, or if the jar size starts to hurt someone in practice.
