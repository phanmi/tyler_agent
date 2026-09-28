SELECT id, workout_name, rep, weight, workout_date
FROM workout_record
WHERE workout_date = ?
ORDER BY id
