package org.tyler.dal.foodrecord;

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
public class FoodRecordDAL implements IFoodRecordDAL {

    private static final Logger log = LoggerFactory.getLogger(FoodRecordDAL.class);

    private final IFoodRecordDAO dao;

    public FoodRecordDAL(IFoodRecordDAO dao) {
        this.dao = dao;
    }

    @Override
    public List<Food> getFoodByDate(String date) {
        requireDate(date);
        return dao.loadByDate(date);
    }

    @Override
    public Food saveFoodByDate(Food food) {
        if (food == null) {
            throw new IllegalArgumentException("Food must not be null");
        }
        String date = dateOf(food);
        requireDate(date);

        dao.saveByDate(date, food);
        log.info("Added food record for {}", date);
        return food;
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
    public boolean deleteFoodFromDate(Food food, String date) {
        requireDate(date);
        if (food == null) {
            throw new IllegalArgumentException("Food must not be null");
        }
        List<FoodRecord> records = dao.loadRecordsByDate(date);
        for (FoodRecord record : records) {
            if (food.equals(record.food())) {
                boolean removed = dao.deleteById(record.id());
                if (removed) {
                    log.info("Deleted one food record for {}", date);
                }
                return removed;
            }
        }
        return false;
    }

    @Override
    public List<Food> getAllFoodRecords() {
        List<Food> result = new ArrayList<>();
        for (Food food : dao.load()) {
            if (food != null) {
                result.add(food);
            }
        }
        return result;
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