# One copy per account, and source removal trusts the receipt

A receipt is keyed by source account and the message's immutable Graph id, not by folder mapping.
A message moved in Outlook from one synced folder to another keeps its id, so it is copied once,
by whichever mapping sees it first. Copying it again for the second folder would be the more
literal reading of "copy this folder", and was rejected because it fills the destination with
duplicates of the same message.

The receipt is the only memory. The service never checks whether a copy still exists at the
destination: a copy you delete at Fastmail stays deleted, and never comes back on the next poll.

Source removal follows the same rule. When `removeSourceAfterSync` is switched on for a folder that
ran without it, the next poll rereads the whole folder and moves every message that has an imported
receipt to Deleted Items, including those whose copy was deleted at the destination since. The
alternative, removing only messages whose copy is still there, would mean searching the destination
for each one, and would keep exactly the messages people deleted as spam. `once --dry-run` lists
what the switch will remove before it happens, and Deleted Items keeps everything recoverable.

Losing the database loses this memory: the next poll copies everything still in the source again.
