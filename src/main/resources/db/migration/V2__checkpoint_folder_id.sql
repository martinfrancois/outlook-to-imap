-- The id of the folder the saved delta link reads. A deleted folder keeps its id in Deleted Items
-- and its link keeps answering, so a poll whose source name now finds another id reads that folder
-- from the start. Checkpoints saved before this column have none, and their folder is read from the
-- start once, because nothing tells which folder their link reads.
ALTER TABLE checkpoint ADD COLUMN folder_id VARCHAR(1000);
