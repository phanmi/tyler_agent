package org.tyler.service.food;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.food.FoodRecord;
import org.tyler.validation.DomainValidation;
import org.tyler.validation.IValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements date-based food record operations.
 *
 * <p>Validates domain input before delegating persistence to {@link IFoodRecordDAO}.
 * Dates come from {@code food.genericInfo().date()}.
 */
@Service
public class FoodService implements IFoodService {

    private static final Logger log = LoggerFactory.getLogger(FoodService.class);

    private final IFoodRecordDAO dao;
    private final IValidator<Food> validator;

    public FoodService(IFoodRecordDAO dao, @Qualifier("foodValidator") IValidator<Food> validator) {
        this.dao = dao;
        this.validator = validator;
    }

    @Override
    public FoodEntry insertFood(Food food) {
        validator.validate(food);
        long id = dao.insert(food);
        log.info("Added food record with id={}", id);
        return new FoodEntry(id, food);
    }

    @Override
    public boolean deleteFoodByDate(String date) {
        DomainValidation.parseDate(date, "date");
        boolean removed = dao.deleteByDate(date);
        if (removed) {
            log.info("Deleted food records for {}", date);
        }
        return removed;
    }

    @Override
    public List<FoodEntry> getFoodRecordsByDate(String date) {
        DomainValidation.parseDate(date, "date");
        List<FoodEntry> result = new ArrayList<>();
        for (FoodRecord record : dao.loadRecordsByDate(date)) {
            result.add(new FoodEntry(record.id(), record.food()));
        }
        return result;
    }

    @Override
    public boolean deleteFoodById(long id) {
        DomainValidation.requireId(id);
        boolean removed = dao.deleteById(id);
        if (removed) {
            log.info("Deleted food record with id={}", id);
        }
        return removed;
    }

}
