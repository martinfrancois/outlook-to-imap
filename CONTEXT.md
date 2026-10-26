# outlook-to-imap

Copies mail one way, from folders of Microsoft accounts into folders on IMAP servers, and remembers
every message it copied so none is copied twice.

## Language

### Where mail comes from

**Source account**:
One Microsoft mailbox the service reads, signed in once with its own login.
_Avoid_: user, mailbox, tenant

**Source folder**:
A folder in a source account, named by a well-known name such as `junkemail` or by a path of
folder names.
_Avoid_: label, category

### Where mail goes

**Destination**:
One IMAP server login that copies are uploaded to.
_Avoid_: target, server, sink

**Destination folder**:
The IMAP folder on a destination that a folder mapping copies into.
_Avoid_: mailbox, label

**Folder mapping**:
One source folder paired with one destination folder, plus whether source removal is on for it.
_Avoid_: rule, route, sync pair

### What happens to a message

**Copy**:
The exact bytes of a source message, uploaded unread into a destination folder. Nothing on the
destination is ever changed or deleted afterwards.
_Avoid_: mirror, forward, move

**Receipt**:
The record that one message of a source account was copied, keyed by the message's immutable
Microsoft id. It is the only memory of a copy: a message with a receipt is never copied again.
_Avoid_: log entry, sync state

**Pending receipt**:
A receipt written before an upload whose outcome is not known yet. It is resolved by looking for
the copy at the destination before uploading again.

**Imported receipt**:
A receipt whose copy the destination has listed back.

**Removed receipt**:
An imported receipt whose source message was moved to Deleted Items.

**Source removal**:
Moving a source message to the account's Deleted Items after its receipt is imported. Never a
permanent delete.
_Avoid_: delete, cleanup, purge

### Running

**Poll**:
One pass over every folder mapping of every source account.
_Avoid_: sync, run, job

**Checkpoint**:
How far a folder mapping has read its source folder, so the next poll reads only what changed.
_Avoid_: cursor, watermark, sync token
