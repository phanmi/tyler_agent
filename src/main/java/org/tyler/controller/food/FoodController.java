package org.tyler.controller.food;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.dal.foodrecord.IFoodRecordDAL;
import org.tyler.model.food.FoodEntry;

import java.util.List;

/**
 * REST controller for food records.
 *
 * <p>Maps routes, binds parameters, and delegates to {@link IFoodRecordDAL}.
 * The optional {@code date} parameter allows missing or blank values to reach
 * the DAL's validation, which throws {@link IllegalArgumentException}.
 * {@link org.tyler.exceptionHandler.GenericExceptionHandler} maps that exception to HTTP 400.
 */
@RestController
@RequestMapping("/api/food")
public class FoodController implements IFoodController {

    private final IFoodRecordDAL foodRecordDAL;

    public FoodController(IFoodRecordDAL foodRecordDAL) {
        this.foodRecordDAL = foodRecordDAL;
    }

    @Override
    @GetMapping
    public List<FoodEntry> foodByDate(@RequestParam(value = "date", required = false) String date) {
        return foodRecordDAL.getFoodRecordsByDate(date);
    }

    @Override
    @DeleteMapping("/{id}")
    public boolean deleteFood(@PathVariable("id") long id) {
        return foodRecordDAL.deleteFoodById(id);
    }

    @Override
    @DeleteMapping("/date/{date}")
    public boolean deleteFoodByDate(@PathVariable("date") String date) {
        return foodRecordDAL.deleteFoodByDate(date);
    }
}
