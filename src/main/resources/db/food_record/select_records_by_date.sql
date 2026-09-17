SELECT id, food_name, amount, unit, calories, protein, carbs, fat, fiber, eaten_date, created_at
FROM food_record
WHERE eaten_date = ?
ORDER BY id