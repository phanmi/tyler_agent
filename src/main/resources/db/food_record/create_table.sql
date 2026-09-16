CREATE TABLE IF NOT EXISTS food_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    food_name TEXT NOT NULL,
    amount NUMERIC NOT NULL,
    unit TEXT NOT NULL,
    calories NUMERIC NOT NULL,
    protein NUMERIC NOT NULL,
    carbs NUMERIC NOT NULL,
    fat NUMERIC NOT NULL,
    fiber NUMERIC NOT NULL,
    eaten_date TEXT NOT NULL,
    created_at TEXT NOT NULL
)