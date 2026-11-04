CREATE TABLE IF NOT EXISTS recognition_usage (
  room TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY(room,day)
);
