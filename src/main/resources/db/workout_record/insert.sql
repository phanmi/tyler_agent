INSERT INTO workout_record (workout_name, rep, weight, workout_date)
VALUES (?, ?, ?, ?) RETURNING id
