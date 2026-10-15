package org.tyler.service.food;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.food.FoodRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements date-based food record operations.
 *
 * <p>Delegates persistence to {@link IFoodRecordDAO} without handling files or JSON.
 * Dates come from {@code food.genericInfo().date()}.
 */
@Service
public class FoodService implements IFoodService {

    private static final Logger log = LoggerFactory.getLogger(FoodService.class);

    private final IFoodRecordDAO dao;

    public FoodService(IFoodRecordDAO dao) {
        this.dao = dao;
    }

    @Override
    public FoodEntry insertFood(Food food) {
        if (food == null) {
            throw new IllegalArgumentException("Food must not be null");
        }
        requireDate(dateOf(food));
        long id = dao.insert(food);
        log.info("Added food record with id={}", id);
        return new FoodEntry(id, food);
    }

    @Override
    public boolean deleteFoodByDate(String date) {
        requireDate(date);
        boolean removed = dao.deleteByDate(date);
        if (removed) {
            log.info("Deleted food records for {}", date);
        }
        return removed;
    }

    @Override
    public List<FoodEntry> getFoodRecordsByDate(String date) {
        requireDate(date);
        List<FoodEntry> result = new ArrayList<>();
        for (FoodRecord record : dao.loadRecordsByDate(date)) {
            result.add(new FoodEntry(record.id(), record.food()));
        }
        return result;
    }

    @Override
    public boolean deleteFoodById(long id) {
        boolean removed = dao.deleteById(id);
        if (removed) {
            log.info("Deleted food record with id={}", id);
        }
        return removed;
    }

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    private static void requireDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("Date must not be blank");
        }
    }
}
