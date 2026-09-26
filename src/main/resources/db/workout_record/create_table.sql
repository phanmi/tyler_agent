CREATE TABLE IF NOT EXISTS workout_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workout_name TEXT NOT NULL,
    rep TEXT NOT NULL,
    weight TEXT NOT NULL,
    workout_date TEXT NOT NULL
)
