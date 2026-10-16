package org.tyler.validation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.dao.workout.IWorkoutDAO;
import org.tyler.model.food.Food;
import org.tyler.model.workout.Workout;
import org.tyler.service.food.FoodService;
import org.tyler.service.workout.WorkoutService;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Verifies discovered validators support typed map injection and existing service selection. */
class ValidatorWiringTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = IValidator.class)
    static class Validators {
        @Bean
        ValidatorMaps validatorMaps(Map<String, IValidator<Food>> food,
                                    Map<String, IValidator<Workout>> workout) {
            return new ValidatorMaps(food, workout);
        }
    }

    record ValidatorMaps(Map<String, IValidator<Food>> food, Map<String, IValidator<Workout>> workout) {
    }

    @Test
    void springCollectsValidatorsByInputTypeAndBeanName() {
        new ApplicationContextRunner().withUserConfiguration(Validators.class, FoodService.class, WorkoutService.class)
                .withBean(IFoodRecordDAO.class, () -> mock(IFoodRecordDAO.class))
                .withBean(IWorkoutDAO.class, () -> mock(IWorkoutDAO.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ValidatorMaps maps = context.getBean(ValidatorMaps.class);
                    assertEquals(Set.of("foodValidator"), maps.food().keySet());
                    assertEquals(Set.of("workoutValidator"), maps.workout().keySet());
                    assertSame(maps.food().get("foodValidator"),
                            ReflectionTestUtils.getField(context.getBean(FoodService.class), "validator"));
                    assertSame(maps.workout().get("workoutValidator"),
                            ReflectionTestUtils.getField(context.getBean(WorkoutService.class), "validator"));
                    assertEquals("Food must not be null", assertThrowsExactly(IllegalArgumentException.class,
                            () -> maps.food().get("foodValidator").validate(null)).getMessage());
                    assertEquals("Workout must not be null", assertThrowsExactly(IllegalArgumentException.class,
                            () -> maps.workout().get("workoutValidator").validate(null)).getMessage());
                });
    }
}
