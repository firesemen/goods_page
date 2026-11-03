CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  room TEXT NOT NULL,
  id TEXT NOT NULL,
  payload TEXT NOT NULL,
  digest TEXT NOT NULL,
  UNIQUE(room, id)
);
CREATE INDEX IF NOT EXISTS events_room_seq ON events(room, seq);
