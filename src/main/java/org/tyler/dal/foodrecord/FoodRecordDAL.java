package org.tyler.dal.foodrecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.model.food.Food;

import java.util.ArrayList;
import java.util.List;

/**
 * 食物记录「按日期」业务语义的实现。
 *
 * <p>只依赖 {@link IFoodRecordDAO} 完成底层读写，自身不触碰文件或 JSON；
 * 日期取值来自 {@code food.genericInfo().date()}。
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
            throw new IllegalArgumentException("Food 不能为空");
        }
        String date = dateOf(food);
        requireDate(date);

        dao.saveByDate(date, food);
        log.info("已追加食物记录：{}", date);
        return food;
    }

    @Override
    public boolean deleteFoodByDate(String date) {
        requireDate(date);
        List<Food> remaining = new ArrayList<>();
        boolean removed = false;
        for (Food food : dao.load()) {
            if (food != null && date.equals(dateOf(food))) {
                removed = true;
            } else {
                remaining.add(food);
            }
        }
        if (removed) {
            dao.save(remaining);
            log.info("已删除 {} 的食物记录", date);
        }
        return removed;
    }

    @Override
    public boolean deleteFoodFromDate(Food food, String date) {
        requireDate(date);
        if (food == null) {
            throw new IllegalArgumentException("Food 不能为空");
        }
        List<Food> foods = new ArrayList<>(dao.load());
        List<Food> remaining = new ArrayList<>();
        boolean removed = false;
        for (Food f : foods) {
            if (!removed && f != null && date.equals(dateOf(f)) && food.equals(f)) {
                removed = true;
            } else {
                remaining.add(f);
            }
        }
        if (removed) {
            dao.save(remaining);
            log.info("已删除 {} 的一条食物记录", date);
        }
        return removed;
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

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    private static void requireDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("日期不能为空");
        }
    }
}