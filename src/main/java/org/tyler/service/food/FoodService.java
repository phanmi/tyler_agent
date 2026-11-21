package org.tyler.service.food;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
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
 * A list of foods is saved in one transaction on the food database.
 * Dates come from {@code food.genericInfo().date()}.
 */
@Service
public class FoodService implements IFoodService {

    private static final Logger log = LoggerFactory.getLogger(FoodService.class);

    private final IFoodRecordDAO dao;
    private final IValidator<Food> validator;
    private final TransactionOperations transactions;

    public FoodService(IFoodRecordDAO dao, @Qualifier("foodValidator") IValidator<Food> validator,
            @Qualifier("foodTransactions") TransactionOperations transactions) {
        this.dao = dao;
        this.validator = validator;
        this.transactions = transactions;
    }

    @Override
    public List<FoodEntry> insertFoods(List<Food> foods) {
        // Every food is validated before the first write, so a rejected list writes nothing.
        foods.forEach(validator::validate);
        List<FoodEntry> saved;
        try {
            saved = transactions.execute(status -> insertAll(foods));
        } catch (TransactionException e) {
            // The transaction could not open, commit or roll back. No DAO call was running, so
            // the failure gets the DAO's write-failure type here and stays a storage failure.
            throw new SQLPersistentException("Failed to save food records", e);
        }
        log.info("Added {} food records", saved.size());
        return saved;
    }

    /** Runs in the caller's transaction: a failed insert rolls back the ones before it. */
    private List<FoodEntry> insertAll(List<Food> foods) {
        List<FoodEntry> saved = new ArrayList<>(foods.size());
        for (Food food : foods) {
            saved.add(new FoodEntry(dao.insert(food), food));
        }
        return List.copyOf(saved);
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
