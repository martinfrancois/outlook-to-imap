# Each user registers their own Microsoft app

Graph only hands out mail to an app registration that the mailbox owner signed in to. The project
ships no client ID: everyone who runs it registers a free app of their own and puts its ID in
`microsoft.clientId`.

A shared client ID would save every user the registration, but the project would then own an app
that thousands of mailboxes have granted mail access to. Microsoft could disable it at any time,
for every user at once, and it would need publisher verification and someone answering for it.
Throttling is the other cost: Outlook allows 10,000 requests in 10 minutes per app per mailbox, and
4 concurrent requests per app per mailbox. Per mailbox means one registration can serve any number
of accounts without them sharing a budget, so one app per user is enough, and one app per account
buys nothing. `accounts[].clientId` exists for people who already have separate registrations, not
for throughput.

A poll costs one request per 100 messages listed, one per message copied and one per message
removed. A first poll of a 9,000-message folder with removal on needs about 18,100, so it is
throttled. The Graph SDK's retry handler waits for `Retry-After` when Microsoft sends one and backs
off exponentially (up to 180 seconds, 5 retries) when it doesn't. When a mailbox is still throttled
after that, the poll stops that account, keeps its checkpoint where it was and goes on with the
next account; receipts make the next poll continue where this one stopped. Tests play 429 and 503
replies against the real SDK: waiting for `Retry-After`, backing off without it, throttling in the
middle of paging, giving up after the retries, and one throttled account not stopping the others.
