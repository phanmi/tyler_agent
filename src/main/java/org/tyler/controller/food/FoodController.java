package org.tyler.controller.food;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.service.food.IFoodService;
import org.tyler.model.food.FoodEntry;

import java.util.List;

/**
 * REST controller for food records.
 *
 * <p>Maps routes, binds parameters, and delegates to {@link IFoodService}.
 * The optional {@code date} parameter allows missing or blank values to reach
 * the service's validation, which throws {@link IllegalArgumentException}.
 * {@link org.tyler.exceptionHandler.GenericExceptionHandler} maps that exception to HTTP 400.
 */
@RestController
@RequestMapping("/api/food")
public class FoodController implements IFoodController {

    private final IFoodService foodService;

    public FoodController(IFoodService foodService) {
        this.foodService = foodService;
    }

    @Override
    @GetMapping
    public List<FoodEntry> foodByDate(@RequestParam(value = "date", required = false) String date) {
        return foodService.getFoodRecordsByDate(date);
    }

    @Override
    @DeleteMapping("/{id}")
    public boolean deleteFood(@PathVariable("id") long id) {
        return foodService.deleteFoodById(id);
    }

    @Override
    @DeleteMapping("/date/{date}")
    public boolean deleteFoodByDate(@PathVariable("date") String date) {
        return foodService.deleteFoodByDate(date);
    }
}
