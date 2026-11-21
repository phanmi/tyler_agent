CREATE TRIGGER fail_integration_food_list BEFORE INSERT ON food_record
WHEN (SELECT COUNT(*) FROM food_record) >= 2
BEGIN SELECT RAISE(ABORT, 'forced integration insert failure'); END
