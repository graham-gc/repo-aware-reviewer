"""Durable event inbox and atomic review claims; no credentials or full webhook payloads are stored."""
import json
from contextlib import contextmanager
import sqlite3
import time
from pathlib import Path


class State:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS events (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, state TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0, available REAL NOT NULL, updated REAL NOT NULL,
                    detail TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS reviews (
                    id TEXT PRIMARY KEY, state TEXT NOT NULL, pipeline INTEGER, updated REAL NOT NULL);
            """)

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def recover(self):
        with self.connect() as db:
            db.execute("UPDATE events SET state='RECEIVED' WHERE state='PROCESSING'")

    def enqueue(self, key, event):
        now = time.time()
        with self.connect() as db:
            # Bound the inbox; completed records expire after a week, review snapshot claims do not auto-expire.
            db.execute("DELETE FROM events WHERE state IN ('DONE','SKIPPED','FAILED') AND updated < ?", (now - 7 * 86400,))
            if db.execute("SELECT COUNT(*) FROM events WHERE state IN ('RECEIVED','PROCESSING')").fetchone()[0] >= 10000:
                raise RuntimeError("Webhook inbox is full")
            return db.execute("INSERT OR IGNORE INTO events(id,payload,state,available,updated) VALUES(?,?,'RECEIVED',?,?)",
                              (key, json.dumps(event), now, now)).rowcount == 1

    def next(self):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM events WHERE state='RECEIVED' AND available<=? ORDER BY available LIMIT 1", (time.time(),)).fetchone()
            if row:
                db.execute("UPDATE events SET state='PROCESSING',attempts=attempts+1,updated=? WHERE id=?", (time.time(), row['id']))
                return row['id'], json.loads(row['payload']), row['attempts'] + 1
        return None

    def finish(self, key, status, detail="", delay=0):
        with self.connect() as db:
            db.execute("UPDATE events SET state=?,detail=?,available=?,updated=? WHERE id=?",
                       (status, detail, time.time() + delay, time.time(), key))

    def claim(self, key):
        with self.connect() as db:
            return db.execute("INSERT OR IGNORE INTO reviews(id,state,updated) VALUES(?,'CLAIMED',?)", (key, time.time())).rowcount == 1

    def triggered(self, key, pipeline=None, status="SENT"):
        with self.connect() as db:
            db.execute("UPDATE reviews SET state=?,pipeline=?,updated=? WHERE id=?", (status, pipeline, time.time(), key))
