CREATE TRIGGER fail_integration_plan BEFORE INSERT ON workout_record
WHEN (SELECT COUNT(*) FROM workout_record) >= 3
BEGIN SELECT RAISE(ABORT, 'forced integration insert failure'); END
