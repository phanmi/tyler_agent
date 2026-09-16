SELECT food_name, amount, unit, calories, protein, carbs, fat, fiber, eaten_date
FROM food_record
WHERE eaten_date = ?
ORDER BY id